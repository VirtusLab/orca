package orca.tools.opencode

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.backend.{
  Conversation,
  Conversations,
  Dispatch,
  LlmBackend,
  LlmResult,
  SessionMode,
  SessionRegistry,
  StreamSource
}
import orca.events.OrcaListener
import orca.llm.{BackendTag, LlmConfig, SessionId, isSafeSessionId}
import orca.subprocess.CliRunner
import orca.tools.opencode.OpencodeApi.{SessionCreateBody, SessionCreated}
import ox.Ox

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** OpenCode backend (ADR 0014). Drives a shared `opencode serve` over HTTP+SSE.
  *
  * Each turn opens its own `GET /event` SSE stream, starts the turn with
  * `prompt_async`, and reads the result off the stream via
  * [[OpencodeConversation]]. The server is created lazily on first use and
  * shared thereafter; per-turn working directories are assumed constant (orca
  * flows run in one repo), so the first call's `workDir` fixes the server's.
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
  def apply(
      cli: CliRunner,
      launcher: OpencodeLauncher = OpencodeLauncher.default
  )(using Ox): OpencodeBackend =
    new OpencodeBackend(workDir =>
      new OpencodeServer(cli, workDir, launcher).http
    )

private[orca] class OpencodeBackend(httpFor: os.Path => OpencodeHttp)(using Ox)
    extends LlmBackend[BackendTag.Opencode.type]:

  private val sessions =
    new SessionRegistry.ClientToServer[BackendTag.Opencode.type]

  // The shared server is built exactly once by a `lazy val`: Scala serialises
  // the initialiser, so `httpFor` can't run twice even under a first-call race.
  // A `lazy val` can't take a parameter, so the first caller's `workDir` — which
  // all turns share — is recorded here for the initialiser to read.
  private val firstWorkDir = new AtomicReference[os.Path]()
  private lazy val sharedServer: OpencodeHttp = httpFor(firstWorkDir.get())

  private def server(workDir: os.Path): OpencodeHttp =
    val _ = firstWorkDir.compareAndSet(null, workDir)
    sharedServer

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Opencode.type],
      config: LlmConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): LlmResult[BackendTag.Opencode.type] =
    val http = server(workDir)
    val source = http.events()
    try
      val conv = openConversation(
        http,
        source,
        serverSessionFor(http, session),
        config,
        prompt,
        outputSchema,
        SessionMode.Autonomous
      )
      val result = Conversations.drainAutonomous(conv, events)
      sessions.commitSuccess(session, result.sessionId)
      result.copy(sessionId = session) // keep the caller's id as the handle
    finally
      source.interrupt() // release the SSE connection at turn end (idempotent)

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Opencode.type],
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.Opencode.type] =
    val http = server(workDir)
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

  override def registerSession(
      client: SessionId[BackendTag.Opencode.type],
      serverSession: SessionId[BackendTag.Opencode.type]
  ): Unit = sessions.commitSuccess(client, serverSession)

  override def serverFor(
      client: SessionId[BackendTag.Opencode.type]
  ): Option[SessionId[BackendTag.Opencode.type]] = sessions.serverFor(client)

  /** Probe `http` for the given session id via `GET /session/<id>` → status
    * 200. Callable directly in tests without going through the lazy-init guard.
    * Returns `false` on any transport error. The [[orca.llm.isSafeSessionId]]
    * guard must have passed before this method is called — it is not re-checked
    * here.
    */
  private[opencode] def probeSession(id: String, http: OpencodeHttp): Boolean =
    try http.getStatus(s"/session/$id") == 200
    catch case NonFatal(_) => false

  /** Best-effort probe: resolves the SERVER id (`ses_…`) mapped to `client`
    * (opencode mints server-side ids; the caller's stable id never matches one)
    * and checks `GET /session/<serverId>` → 200. Returns `false` — safe re-seed
    * — when no server id is mapped (the map hasn't been rehydrated from the
    * log, so there is no known live session), the opencode server has not been
    * started yet, the request fails for any reason, or the id fails the
    * [[orca.llm.isSafeSessionId]] guard (blocks URL injection such as `a/b`
    * routing to a different endpoint).
    *
    * The client→server map is persisted in the progress log and rehydrated on
    * resume (D2), so on a resumed run this targets the right server id.
    */
  override def sessionExists(
      session: SessionId[BackendTag.Opencode.type]
  ): Boolean =
    sessions.serverFor(session) match
      case None => false
      case Some(serverSession) =>
        val id = SessionId.value(serverSession)
        if !isSafeSessionId(id) then false
        else
          try
            if firstWorkDir.get() == null then false
            else probeSession(id, sharedServer)
          catch case NonFatal(_) => false

  /** The server `ses_…` to drive: a fresh `POST /session`, or the one a prior
    * turn registered for this caller id.
    */
  private def serverSessionFor(
      http: OpencodeHttp,
      session: SessionId[BackendTag.Opencode.type]
  ): String =
    sessions.dispatchFor(session) match
      case Dispatch.Resume(serverId) => SessionId.value(serverId)
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
      config: LlmConfig,
      prompt: String,
      outputSchema: Option[String],
      mode: SessionMode
  ): OpencodeConversation =
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
