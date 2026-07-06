package orca.tools.opencode

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.backend.{
  Conversation,
  Conversations,
  Dispatch,
  AgentBackend,
  AgentResult,
  SessionMode,
  SessionRegistry,
  SessionSupport,
  StreamSource
}
import orca.events.OrcaListener
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  SessionId,
  ToolSet,
  WireSessionId
}
import orca.subprocess.CliRunner
import orca.tools.opencode.OpencodeApi.{SessionCreateBody, SessionCreated}
import ox.Ox

import scala.util.control.NonFatal

/** Lifecycle seam between the backend and the shared `opencode serve` owner —
  * lets tests substitute a fake without a real process. `http` may spawn on
  * first force; `started` must never spawn (probes use it to answer "absent"
  * without side effects); `close()` is idempotent.
  */
private[opencode] trait OpencodeServerHandle:
  def http: OpencodeHttp
  def started: Boolean
  def close(): Unit

/** OpenCode backend (ADR 0014). Drives a shared `opencode serve` over HTTP+SSE.
  *
  * Each turn opens its own `GET /event` SSE stream, starts the turn with
  * `prompt_async`, and reads the result off the stream via
  * [[OpencodeConversation]]. The single [[OpencodeServerHandle]] is built once
  * at construction ([[OpencodeBackend.apply]]) and shared across turns; the
  * process spawn behind it stays lazy.
  *
  * OpenCode mints `ses_…` ids, so — like Codex — a
  * [[SessionRegistry.ClientToServer]] maps the caller's stable id to the server
  * id; [[runAutonomous]] returns the caller's id so it stays the handle.
  *
  * A turn relies on the server emitting a terminal
  * `session.idle`/`session.error` (or closing the SSE stream); there is no
  * per-turn timeout at this layer, so an orchestrator-level deadline (the
  * flow's own timeout) is the backstop for a server that wedges mid-turn.
  */
private[orca] object OpencodeBackend:
  /** Build the backend with its server fixed at construction. Per-turn working
    * directories are assumed constant (orca flows run in one repo), so the
    * server's `workDir` is pinned here; the per-call `workDir` SPI parameter on
    * [[OpencodeBackend.runAutonomous]]/[[OpencodeBackend.runInteractive]]
    * remains for the interface but is ignored by opencode.
    */
  def apply(
      cli: CliRunner,
      workDir: os.Path,
      launcher: OpencodeLauncher = OpencodeLauncher.default
  )(using Ox): OpencodeBackend =
    new OpencodeBackend(new OpencodeServer(cli, workDir, launcher))

private[orca] class OpencodeBackend(server: OpencodeServerHandle)
    extends AgentBackend[BackendTag.Opencode.type]:

  /** Tear down the shared `opencode serve` process and its drain forks. A no-op
    * if the server was never started (opencode wired but unused). Called by the
    * runtime in the flow body's `finally`, before the flow scope joins forks
    * (see [[orca.backend.AgentBackend.close]]).
    */
  override def close(): Unit = server.close()

  private val registry =
    new SessionRegistry.ClientToServer[BackendTag.Opencode.type]

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Opencode.type],
      config: AgentConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): AgentResult[BackendTag.Opencode.type] =
    val http = server.http
    Conversations.runAutonomous(session, registry, events):
      openConversation(
        http,
        http.events(),
        serverSessionFor(http, session),
        config,
        prompt,
        outputSchema,
        SessionMode.Autonomous
      )

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Opencode.type],
      displayPrompt: String,
      config: AgentConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.Opencode.type] =
    val http = server.http
    // The returned conversation owns its stream: it interrupts on the terminal
    // event or `cancel`, so no scope-level backstop is needed here.
    openConversation(
      http,
      http.events(),
      serverSessionFor(http, session),
      config,
      prompt,
      outputSchema,
      SessionMode.Interactive(displayPrompt)
    )

  /** Probe `http` for the given session id via `GET /session/<id>` → status
    * 200. Callable directly in tests without going through the lazy-init guard.
    * Returns `false` on any transport error. The
    * [[orca.agents.isSafeSessionId]] guard must have passed before this method
    * is called — it is not re-checked here.
    */
  private[opencode] def probeSession(id: String, http: OpencodeHttp): Boolean =
    try http.getStatus(s"/session/$id") == 200
    catch case NonFatal(_) => false

  /** OpenCode's `ses_…` sessions are server-side and durable, so it is
    * [[SessionSupport.Durable]]: the client→server map is persisted to the
    * progress log and rehydrated on resume. Existence probes the SERVER id the
    * registry resolves for a client (opencode mints server-side ids; the
    * caller's stable id never matches one) via `GET /session/<serverId>` → 200.
    *
    * Because [[SessionSupport.exists]] is registry-gated, `false` results when
    * no server id is mapped (the map hasn't been rehydrated ⇒ no known live
    * session), the opencode server has not been started yet, the request fails
    * for any reason, or the server id fails the [[orca.agents.isSafeSessionId]]
    * guard (blocks URL injection such as `a/b` routing to a different
    * endpoint).
    */
  val tag: BackendTag.Opencode.type = BackendTag.Opencode

  override def enforcement(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): Enforcement =
    OpencodeArgs.enforcement(tools, autoApprove)

  val sessions: SessionSupport[BackendTag.Opencode.type] =
    SessionSupport.Durable(
      registry,
      id => server.started && probeSession(id, server.http)
    )

  /** The server `ses_…` to drive: a fresh `POST /session`, or the one a prior
    * turn registered for this caller id.
    */
  private def serverSessionFor(
      http: OpencodeHttp,
      session: SessionId[BackendTag.Opencode.type]
  ): String =
    registry.dispatchFor(session) match
      case Dispatch.Resume(serverId) => WireSessionId.value(serverId)
      case Dispatch.Fresh(_) =>
        val resp = http.postJson("/session", writeToString(SessionCreateBody()))
        readFromString[SessionCreated](resp).id

  /** Open the SSE stream (reader running) **then** fire `prompt_async`, so no
    * turn events are missed. The conversation derives the result from the
    * stream.
    */
  private def openConversation(
      http: OpencodeHttp,
      source: StreamSource,
      serverSession: String,
      config: AgentConfig,
      prompt: String,
      outputSchema: Option[String],
      mode: SessionMode
  )(using Ox): OpencodeConversation =
    val displayPrompt = mode match
      case SessionMode.Interactive(p) => p
      case SessionMode.Autonomous     => ""
    val canAsk = mode match
      case _: SessionMode.Interactive => true
      case SessionMode.Autonomous     => false
    val conv = new OpencodeConversation(
      source,
      http,
      serverSession,
      outputSchema,
      canAsk,
      initialPrompt = displayPrompt
    )
    val body = OpencodeArgs.message(config, prompt, outputSchema, mode)
    try
      val _ = http.postJson(
        s"/session/$serverSession/prompt_async",
        writeToString(body)
      )
    catch
      // The reader is already live on the SSE stream; cancel it so it doesn't
      // sit blocked until scope teardown if the turn never started.
      case e: Throwable =>
        conv.cancel()
        throw e
    conv
