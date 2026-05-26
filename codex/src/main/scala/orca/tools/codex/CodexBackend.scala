package orca.tools.codex

import orca.events.OrcaListener
import orca.llm.{BackendTag, LlmConfig, SessionId}
import orca.OrcaFlowException
import orca.backend.{
  Conversation,
  Conversations,
  LlmBackend,
  LlmResult,
  SessionMode
}
import orca.subprocess.CliRunner

/** Codex backend. Both autonomous and interactive paths drive `codex exec
  * --json` over stdio: stdout JSONL is parsed into [[InboundEvent]]s, and the
  * assistant message preceding `turn.completed` becomes the result. See
  * [[../../../adr/0007-codex-exec-jsonl-driver.md ADR 0007]] for the shape of
  * the protocol and the rationale for not using the experimental WebSocket
  * app-server.
  *
  * Both modes wrap the subprocess in a [[CodexConversation]]; the autonomous
  * path drains it internally via [[orca.backend.Conversations.drainAutonomous]]
  * while the interactive path returns the conversation for an `Interaction` to
  * drive. Multi-turn happens via `continueInteractive` / `continueAutonomous`,
  * which spawn a fresh `codex exec resume <thread_id>`.
  */
class CodexBackend(cli: CliRunner) extends LlmBackend[BackendTag.Codex.type]:

  /** Maps the client-allocated session id (the UUID the caller passes around)
    * to codex's server-allocated thread id (returned in the first response's
    * `thread.started` event). `codex exec` doesn't accept a caller-supplied
    * id, so we mint a UUID upfront, learn the real thread id after the first
    * call, and use it for `codex exec resume` on subsequent calls.
    *
    * Per-backend instance; the backend is a per-flow singleton, so the map
    * stays small (one entry per active session).
    */
  private val clientToServer =
    new java.util.concurrent.ConcurrentHashMap[String, String]()

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Codex.type],
      config: LlmConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop
  ): LlmResult[BackendTag.Codex.type] =
    val conv = openConversation(
      prompt = prompt,
      mode = SessionMode.Autonomous,
      session = session,
      config = config,
      workDir = workDir,
      // codex `exec resume` rejects `--output-schema`, and autonomous
      // structured calls already wrap the prompt with the schema via
      // DefaultLlmCall's template.
      outputSchema = None
    )
    try
      val result = Conversations.drainAutonomous(conv, events)
      rememberServerId(session, result.sessionId)
      // Hide the server-allocated id from the caller — they keep using the
      // client id they passed in. Future calls resolve via clientToServer.
      result.copy(sessionId = session)
    catch
      case e: OrcaFlowException =>
        throw new OrcaFlowException(s"codex CLI failed: ${e.getMessage}")

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Codex.type],
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.Codex.type] =
    openConversation(
      prompt,
      mode = SessionMode.Interactive(displayPrompt),
      session = session,
      config = config,
      workDir = workDir,
      outputSchema = outputSchema
    )

  /** Spawn `codex exec --json` (fresh) or `codex exec resume <server-id>`
    * (continuation), and wrap the process in a live [[CodexConversation]].
    * Stdin is closed immediately — codex consumes the prompt argv-side.
    *
    * The fresh-vs-resume decision is driven by [[clientToServer]]: if we've
    * seen this client session id before, we know the server id to resume;
    * otherwise we start fresh and the post-call [[rememberServerId]] records
    * the mapping.
    */
  private def openConversation(
      prompt: String,
      mode: SessionMode,
      session: SessionId[BackendTag.Codex.type],
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.Codex.type] =
    val displayPrompt = mode match
      case SessionMode.Interactive(p) => p
      case SessionMode.Autonomous     => ""
    val finalPrompt = mergeSystemPrompt(config, prompt)
    val schemaFile = writeSchemaIfPresent(outputSchema, workDir)
    val args = Option(clientToServer.get(SessionId.value(session))) match
      case Some(serverId) =>
        CodexArgs.execResume(
          SessionId[BackendTag.Codex.type](serverId),
          finalPrompt,
          config
        )
      case None => CodexArgs.exec(finalPrompt, config, schemaFile, workDir)
    val process = cli.spawnPiped(args, cwd = workDir, pipeStderr = true)
    try
      // codex doesn't accept user turns over stdin once the initial
      // prompt has been argv-supplied; close immediately so the
      // child stops waiting on stdin EOF. Same reflex as claude's
      // single-shot stream-json path.
      process.closeStdin()
      new CodexConversation(
        process,
        initialPrompt = displayPrompt,
        outputSchema = outputSchema
      )
    catch
      case e: Exception =>
        process.sendSigInt()
        throw OrcaFlowException(
          s"Failed to open codex session: ${e.getMessage}"
        )

  /** Record the server-allocated thread id learned from the first call's
    * response so subsequent calls with the same client id resume that thread.
    * No-op if the mapping already exists (idempotent under retry).
    */
  private def rememberServerId(
      clientId: SessionId[BackendTag.Codex.type],
      serverId: SessionId[BackendTag.Codex.type]
  ): Unit =
    val _ = clientToServer.putIfAbsent(
      SessionId.value(clientId),
      SessionId.value(serverId)
    )

  /** codex `exec` has no `--system-prompt` flag (codex picks up `AGENTS.md`
    * files in the working directory for static instructions). Fold a configured
    * `systemPrompt` into the user prompt as a preamble — a low-tech but
    * predictable substitute.
    */
  private def mergeSystemPrompt(config: LlmConfig, userPrompt: String): String =
    config.systemPrompt match
      case None => userPrompt
      case Some(body) =>
        s"""System guidance:
           |$body
           |
           |User request:
           |$userPrompt""".stripMargin

  private def writeSchemaIfPresent(
      schema: Option[String],
      workDir: os.Path
  ): Option[os.Path] =
    schema.map: body =>
      val file = workDir / ".codex" / "orca-output-schema.json"
      os.write.over(file, body, createFolders = true)
      file
