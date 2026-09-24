package orca.tools.opencode

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.backend.{
  AskUserChannel,
  LiveTurn,
  TurnRequest,
  Dispatch,
  AgentBackend,
  TurnMode,
  IdScheme,
  SessionSupport,
  StreamSource
}
import orca.agents.{
  Model,
  AutoApprove,
  BackendTag,
  AgentConfig,
  EnforcementCell,
  StructuredOutputMode,
  ToolSet,
  TurnDispatch,
  WireSessionId
}
import orca.events.OrcaListener
import orca.subprocess.CliRunner
import orca.tools.opencode.OpencodeApi.{SessionCreateBody, SessionCreated}
import ox.Ox

import scala.util.control.NonFatal

/** Seam between the backend and the shared `opencode serve` owner — lets tests
  * substitute a fake without a real process. `http` may spawn on first call.
  */
private[opencode] trait OpencodeServerHandle:
  def http(): OpencodeHttp

/** OpenCode backend (ADR 0014). Drives a shared `opencode serve` over HTTP+SSE.
  *
  * Each turn opens its own `GET /event` SSE stream, starts the turn with
  * `prompt_async`, and reads the result off the stream via [[OpencodeTurn]].
  * The single [[OpencodeServerHandle]] is built once at construction and shared
  * across turns; the process spawn behind it stays lazy.
  *
  * A turn relies on the server emitting a terminal
  * `session.idle`/`session.error` (or closing the SSE stream). There is no
  * per-turn timeout at this layer, nor at any other — a server that wedges
  * mid-turn wedges the flow until the run is cancelled.
  */
private[orca] object OpencodeBackend:
  /** Build the backend with its server fixed at construction. orca flows run in
    * one repo, so the server's `workDir` is pinned here — the same value
    * [[AgentBackend.workDir]] exposes.
    */
  def apply(
      cli: CliRunner,
      workDir: os.Path,
      events: OrcaListener,
      launcher: OpencodeLauncher = OpencodeLauncher.default
  )(using Ox): OpencodeBackend =
    new OpencodeBackend(
      OpencodeServer(cli, workDir, events, launcher),
      workDir
    )

  /** Tool name the server injects for a `format: json_schema` turn: the model
    * delivers the payload by calling it. [[OpencodeTurn]] suppresses that echo
    * — the payload reaches the caller as `OrcaEvent.StructuredResult`, so
    * rendering the tool call too would show the same JSON twice.
    */
  private[opencode] val StructuredOutputToolName: String = "StructuredOutput"

  /** Shared by [[OpencodeBackend.structuredOutputMode]] and [[OpencodeTurn]],
    * so prompt assembly and the drain can't disagree about how the payload
    * arrives.
    */
  private[opencode] val StructuredOutputDelivery: StructuredOutputMode =
    StructuredOutputMode.Tool

/** `server` and `workDir` must agree; `apply` is the only production
  * constructor.
  */
private[orca] class OpencodeBackend(
    server: OpencodeServerHandle,
    override val workDir: os.Path = os.pwd
) extends AgentBackend[BackendTag.Opencode.type]:

  /** Probe `http` for the given session id via `GET /session/<id>` → status
    * 200; `false` on any transport error. The [[orca.agents.SessionId.isSafe]]
    * guard must have passed before this is called — it is not re-checked here.
    */
  private[opencode] def probeSession(id: String, http: OpencodeHttp): Boolean =
    try http.getStatus(s"/session/$id") == 200
    catch case NonFatal(_) => false

  val tag: BackendTag.Opencode.type = BackendTag.Opencode

  export OpencodeArgs.enforcementCell

  /** The `format: json_schema` message field makes the server inject a
    * `StructuredOutput` tool the model answers with. Probed against opencode
    * 1.17.10 (2026-08-09): such a turn finishes with `finish: "tool-calls"` and
    * delivers the validated object through that tool (also on
    * `info.structured`) — never as reply text.
    */
  override def structuredOutputMode: StructuredOutputMode =
    OpencodeBackend.StructuredOutputDelivery

  // Provider-matched so incidental work doesn't pull in a second provider's
  // auth: an openai-led agent's cheap is an openai model, otherwise anthropic
  // haiku. Reads the provider prefix directly (not OpencodeModel.split, which
  // throws on a bare id) so resolving cheap can never break a flow.
  def cheapModel(leading: Option[Model]): Option[Model] =
    leading.map(m => Model.name(m).takeWhile(_ != '/')) match
      case Some("openai") => Some(OpencodeModels.OpenaiLuna)
      case _              => Some(OpencodeModels.AnthropicHaiku)

  /** The sole session handle. [[IdScheme.ServerMinted]]: the caller's stable id
    * maps to opencode's server-minted `ses_…` id, so subsequent turns resume
    * it.
    */
  val sessions: SessionSupport[BackendTag.Opencode.type] =
    SessionSupport.durable(
      IdScheme.ServerMinted,
      // No `server.started &&` guard: opencode persists sessions in a global
      // on-disk DB that a freshly (lazily) spawned server resumes, so the probe
      // must be allowed to force the spawn. Safe because
      // `SessionSupport` probes a server-minted id only when one is mapped, so
      // the forced spawn only fires on a genuine resume — when the server is
      // about to be needed anyway.
      id => probeSession(id, server.http())
    )

  /** The server `ses_…` to drive: a fresh `POST /session`, or the one
    * `dispatch` resumes.
    */
  private def serverSessionFor(
      http: OpencodeHttp,
      dispatch: Dispatch[BackendTag.Opencode.type]
  ): String =
    dispatch match
      case Dispatch.Resume(serverId, _) => WireSessionId.value(serverId)
      case Dispatch.Fresh(_) =>
        val resp = http.postJson("/session", writeToString(SessionCreateBody()))
        readFromString[SessionCreated](resp).id

  /** Resolve the server session, THEN open the SSE stream: [[serverSessionFor]]
    * can throw (fresh `POST /session`, or a bad resume id), and opening the
    * stream first would leak the `GET /event` connection on that failure. The
    * `try`/`catch` is defense-in-depth for any throw between the stream opening
    * and [[openConversation]] handing it to the owning [[OpencodeTurn]] (whose
    * own `catch` only covers the later `prompt_async` POST). The conversation
    * owns its stream: it interrupts on the terminal event or `cancel`.
    */
  override protected[orca] def open(
      turn: TurnRequest[BackendTag.Opencode.type]
  )(using Ox): LiveTurn[BackendTag.Opencode.type] =
    import turn.*
    val http = server.http()
    val serverSession = serverSessionFor(http, dispatch)
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
    * turn events are missed. [[open]] resolves the server session and opens
    * `source` in the leak-safe order.
    */
  private def openConversation(
      http: OpencodeHttp,
      source: StreamSource,
      serverSession: String,
      config: AgentConfig,
      prompt: String,
      outputSchema: Option[String],
      mode: TurnMode
  )(using Ox): LiveTurn[BackendTag.Opencode.type] =
    val live = OpencodeTurn(
      source,
      http,
      serverSession,
      outputSchema,
      askUser =
        if mode.isInteractive then AskUserChannel.Native
        else AskUserChannel.Unavailable,
      openingPrompt = mode.openingPrompt
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
        live.cancel()
        throw e
    live
