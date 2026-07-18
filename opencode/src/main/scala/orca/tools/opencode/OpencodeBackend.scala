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
  StructuredOutputMode,
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
  * at construction and shared across turns; the process spawn behind it stays
  * lazy.
  *
  * A turn relies on the server emitting a terminal
  * `session.idle`/`session.error` (or closing the SSE stream); there is no
  * per-turn timeout at this layer, so the flow's own timeout is the backstop
  * for a server that wedges mid-turn.
  */
private[orca] object OpencodeBackend:
  /** Build the backend with its server fixed at construction. orca flows run in
    * one repo, so the server's `workDir` is pinned here — the same value
    * [[AgentBackend.workDir]] exposes.
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
    * if the server was never started. Called in the flow body's `finally`,
    * before the flow scope joins forks.
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
    * 200; `false` on any transport error. The [[orca.agents.SessionId.isSafe]]
    * guard must have passed before this is called — it is not re-checked here.
    */
  private[opencode] def probeSession(id: String, http: OpencodeHttp): Boolean =
    try http.getStatus(s"/session/$id") == 200
    catch case NonFatal(_) => false

  val tag: BackendTag.Opencode.type = BackendTag.Opencode

  override def enforcement(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): Enforcement =
    OpencodeArgs.enforcement(tools, autoApprove)

  /** The `format: json_schema` message field is a response-format constraint —
    * the model still emits the JSON as its reply text (the server parses it
    * into `structured`); there is no structured-output tool.
    */
  override def structuredOutputMode: StructuredOutputMode =
    StructuredOutputMode.RawText

  /** The sole session handle. [[IdScheme.ServerMinted]]: the caller's stable id
    * maps to opencode's server-minted `ses_…` id, so subsequent turns resume
    * it. The bookkeeping is encapsulated; the spawn/commit paths go through
    * `sessions.dispatchFor` / `Conversations.runAutonomous(session, sessions,
    * …)`.
    */
  val sessions: SessionSupport[BackendTag.Opencode.type] =
    SessionSupport.durable(
      IdScheme.ServerMinted,
      // No `server.started &&` guard: opencode persists sessions in a global
      // on-disk DB that a freshly (lazily) spawned server resumes, so the probe
      // must be allowed to force the spawn. Safe because
      // `SessionSupport.willContinue` short-circuits on no mapped wire id, so
      // the forced spawn only fires on a genuine resume — when the server is
      // about to be needed anyway.
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

  /** Resolve the server session, THEN open the SSE stream: [[serverSessionFor]]
    * can throw (fresh `POST /session`, or a bad resume id), and opening the
    * stream first would leak the `GET /event` connection on that failure. The
    * `try`/`catch` is defense-in-depth for any throw between the stream opening
    * and [[openConversation]] handing it to the owning [[OpencodeConversation]]
    * (whose own `catch` only covers the later `prompt_async` POST).
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

  /** Start the reader on the SSE stream **then** fire `prompt_async`, so no
    * turn events are missed. Callers ([[startTurn]]) resolve the server session
    * and open `source` in the leak-safe order.
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
