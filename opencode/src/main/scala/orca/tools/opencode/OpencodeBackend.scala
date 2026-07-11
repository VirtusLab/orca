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
  ConversationMode,
  IdScheme,
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
  * first force; `close()` is idempotent.
  */
private[opencode] trait OpencodeServerHandle:
  def http: OpencodeHttp
  def close(): Unit

/** OpenCode backend (ADR 0014). Drives a shared `opencode serve` over HTTP+SSE.
  *
  * Each turn opens its own `GET /event` SSE stream, starts the turn with
  * `prompt_async`, and reads the result off the stream via
  * [[OpencodeConversation]]. The single [[OpencodeServerHandle]] is built once
  * at construction ([[OpencodeBackend.apply]]) and shared across turns; the
  * process spawn behind it stays lazy.
  *
  * OpenCode mints `ses_…` ids, so — like Codex — a [[IdScheme.ServerMinted]]
  * bookkeeping maps the caller's stable id to the server id; [[runAutonomous]]
  * returns the caller's id so it stays the handle.
  *
  * A turn relies on the server emitting a terminal
  * `session.idle`/`session.error` (or closing the SSE stream); there is no
  * per-turn timeout at this layer, so an orchestrator-level deadline (the
  * flow's own timeout) is the backstop for a server that wedges mid-turn.
  */
private[orca] object OpencodeBackend:
  /** Build the backend with its server fixed at construction. Per-turn working
    * directories are assumed constant (orca flows run in one repo), so the
    * server's `workDir` is pinned here — and is the SAME value
    * [[AgentBackend.workDir]] exposes, by construction.
    */
  def apply(
      cli: CliRunner,
      workDir: os.Path,
      launcher: OpencodeLauncher = OpencodeLauncher.default
  )(using Ox): OpencodeBackend =
    new OpencodeBackend(new OpencodeServer(cli, workDir, launcher), workDir)

/** `server` and `workDir` must agree; `apply` is the only production
  * constructor.
  */
private[orca] class OpencodeBackend(
    server: OpencodeServerHandle,
    override val workDir: os.Path = os.pwd
) extends AgentBackend[BackendTag.Opencode.type]:

  /** Tear down the shared `opencode serve` process and its drain forks. A no-op
    * if the server was never started (opencode wired but unused). Called by the
    * runtime in the flow body's `finally`, before the flow scope joins forks
    * (see [[orca.backend.AgentBackend.close]]).
    */
  override def close(): Unit = server.close()

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Opencode.type],
      config: AgentConfig,
      events: OrcaListener,
      outputSchema: Option[String]
  ): AgentResult[BackendTag.Opencode.type] =
    val http = server.http
    Conversations.runAutonomous(session, sessions, events):
      startTurn(
        http,
        session,
        config,
        prompt,
        outputSchema,
        ConversationMode.Autonomous
      )

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Opencode.type],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.Opencode.type] =
    val http = server.http
    // The returned conversation owns its stream: it interrupts on the terminal
    // event or `cancel`, so no scope-level backstop is needed here.
    startTurn(
      http,
      session,
      config,
      prompt,
      outputSchema,
      ConversationMode.Interactive(displayPrompt)
    )

  /** Probe `http` for the given session id via `GET /session/<id>` → status
    * 200. Callable directly in tests without going through the lazy-init guard.
    * Returns `false` on any transport error. The
    * [[orca.agents.SessionId.isSafe]] guard must have passed before this method
    * is called — it is not re-checked here.
    */
  private[opencode] def probeSession(id: String, http: OpencodeHttp): Boolean =
    try http.getStatus(s"/session/$id") == 200
    catch case NonFatal(_) => false

  /** OpenCode's `ses_…` sessions are server-side and durable: the client→server
    * map is persisted to the progress log and rehydrated on resume. Existence
    * probes the SERVER id resolved for a client (opencode mints server-side
    * ids; the caller's stable id never matches one) via `GET
    * /session/<serverId>` → 200.
    *
    * Because [[SessionSupport.willContinue]] resolves the recorded mapping
    * first, `false` results when no server id is mapped — which includes a
    * server id that failed the [[orca.agents.SessionId.isSafe]] guard at commit
    * time (blocks URL injection such as `a/b` routing to a different endpoint):
    * `register`/`commitAfterDrain` refuse to record it — as well as the map not
    * having been rehydrated yet (⇒ no known live session), or the request
    * failing for any reason.
    *
    * Sessions genuinely outlive the process: opencode persists them to a global
    * on-disk store shared by every `opencode serve` on the machine
    * (cwd-independent), so a fresh server spawned after a kill/restart resumes
    * a prior run's `resumeWireId` — `GET /session/<id>` returns 200 with full
    * message history (live-verified 2026-07-08). The probe therefore forces
    * `server.http` (the lazy spawn) rather than gating on whether the server
    * has already started: on resume the registry has a mapped id but no turn
    * has necessarily touched the server yet, so gating on "already spawned"
    * would answer "absent" before ever asking the (perfectly capable) fresh
    * server. This is safe because [[SessionSupport.willContinue]]
    * short-circuits on no mapped id, so the forced spawn only fires when there
    * is a wire id to resume — i.e. precisely when the server is about to be
    * needed for a turn anyway. The per-run server process is an implementation
    * detail, not a durability boundary.
    */
  val tag: BackendTag.Opencode.type = BackendTag.Opencode

  override def enforcement(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): Enforcement =
    OpencodeArgs.enforcement(tools, autoApprove)

  /** The sole session handle. [[IdScheme.ServerMinted]]: the caller's stable id
    * maps to opencode's server-minted `ses_…` id, so subsequent turns resume
    * it. The bookkeeping is encapsulated; the spawn/commit paths go through
    * `sessions.dispatchFor` / `Conversations.runAutonomous(session, sessions,
    * …)`.
    */
  val sessions: SessionSupport[BackendTag.Opencode.type] =
    SessionSupport.durable(
      IdScheme.ServerMinted,
      // No `server.started &&` guard: opencode persists sessions in its
      // global on-disk DB, and a freshly (lazily) spawned server resumes
      // them, so the probe must be allowed to force the spawn — gating on
      // "already started" would short-circuit the very first resume probe
      // of a run, before anything had forced `server.http` yet. Safe because
      // `SessionSupport.willContinue` short-circuits on no mapped wire id,
      // so the forced spawn only fires on a genuine resume, when the server
      // is about to be needed for a turn anyway.
      id => probeSession(id, server.http)
    )

  /** The server `ses_…` to drive: a fresh `POST /session`, or the one a prior
    * turn registered for this caller id.
    */
  private def serverSessionFor(
      http: OpencodeHttp,
      session: SessionId[BackendTag.Opencode.type]
  ): String =
    sessions.dispatchFor(session) match
      case Dispatch.Resume(serverId) => WireSessionId.value(serverId)
      case Dispatch.Fresh(_) =>
        val resp = http.postJson("/session", writeToString(SessionCreateBody()))
        readFromString[SessionCreated](resp).id

  /** Resolve the server session, THEN open the SSE stream —
    * [[serverSessionFor]] can throw (a fresh `POST /session`, or a bad resume
    * id), and Scala evaluates call arguments left-to-right, so putting
    * `http.events()` in argument position ahead of it used to open a live `GET
    * /event` connection that a subsequent `serverSessionFor` failure then
    * discarded uninterrupted (leaking the connection/socket). Resolving the
    * session first means that failure never gets near the stream at all. The
    * `try`/`catch` is defense-in-depth for any throw between the stream opening
    * and [[openConversation]] handing it to the [[OpencodeConversation]] that
    * owns it (that method's own `catch` only covers the later `prompt_async`
    * POST).
    */
  private def startTurn(
      http: OpencodeHttp,
      session: SessionId[BackendTag.Opencode.type],
      config: AgentConfig,
      prompt: String,
      outputSchema: Option[String],
      mode: ConversationMode
  ): OpencodeConversation =
    val serverSession = serverSessionFor(http, session)
    val source = http.events()
    try
      openConversation(
        http,
        source,
        serverSession,
        config,
        prompt,
        outputSchema,
        mode
      )
    catch
      case e: Throwable =>
        source.interrupt()
        throw e

  /** Open the SSE stream (reader running) **then** fire `prompt_async`, so no
    * turn events are missed. The conversation derives the result from the
    * stream. Callers ([[startTurn]]) are responsible for resolving the server
    * session and opening `source` in the leak-safe order.
    */
  private def openConversation(
      http: OpencodeHttp,
      source: StreamSource,
      serverSession: String,
      config: AgentConfig,
      prompt: String,
      outputSchema: Option[String],
      mode: ConversationMode
  ): OpencodeConversation =
    val displayPrompt = mode.displayPrompt
    val canAsk = mode.isInteractive
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
