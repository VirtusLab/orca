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

  def runAutonomous(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop
  ): LlmResult[BackendTag.Codex.type] =
    invokeAutonomous(prompt, config, workDir, resume = None, events)

  def continueAutonomous(
      sessionId: SessionId[BackendTag.Codex.type],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop
  ): LlmResult[BackendTag.Codex.type] =
    invokeAutonomous(prompt, config, workDir, resume = Some(sessionId), events)

  def runInteractive(
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.Codex.type] =
    openConversation(
      prompt,
      mode = SessionMode.Interactive(displayPrompt),
      config,
      workDir,
      resume = None,
      outputSchema
    )

  def continueInteractive(
      sessionId: SessionId[BackendTag.Codex.type],
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.Codex.type] =
    openConversation(
      prompt,
      mode = SessionMode.Interactive(displayPrompt),
      config,
      workDir,
      resume = Some(sessionId),
      // codex exec resume doesn't accept --output-schema; structured
      // validation on resume falls to the prompt template + post-hoc
      // parsing in DefaultLlmCall.
      outputSchema = None
    )

  /** Spawn `codex exec --json` and wrap the process in a live
    * [[CodexConversation]]. Stdin is closed immediately — codex consumes the
    * prompt argv-side and reads stdin only as an appended `<stdin>` block,
    * which we don't use.
    *
    * `mode` only affects the opening `UserMessage` event the conversation
    * surfaces to the renderer (`Interactive` carries the `displayPrompt`,
    * `Autonomous` enqueues nothing). codex has no MCP / `ask_user` flow, so the
    * autonomous/interactive distinction is otherwise wire-identical.
    */
  private def openConversation(
      prompt: String,
      mode: SessionMode,
      config: LlmConfig,
      workDir: os.Path,
      resume: Option[SessionId[BackendTag.Codex.type]],
      outputSchema: Option[String]
  ): Conversation[BackendTag.Codex.type] =
    val displayPrompt = mode match
      case SessionMode.Interactive(p) => p
      case SessionMode.Autonomous     => ""
    val finalPrompt = mergeSystemPrompt(config, prompt)
    val schemaFile = writeSchemaIfPresent(outputSchema, workDir)
    val args = resume match
      case Some(sid) => CodexArgs.execResume(sid, finalPrompt, config)
      case None      => CodexArgs.exec(finalPrompt, config, schemaFile, workDir)
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

  /** Autonomous invocation: open a [[CodexConversation]] over the same JSONL
    * subprocess the interactive path uses, then drain it via
    * [[Conversations.drainAutonomous]] to get the result. The conversation's
    * own reader thread + stderr drain (in [[orca.backend.StreamConversation]])
    * handle the lifecycle bits that used to live in a bespoke `drainHeadless`
    * here.
    */
  private def invokeAutonomous(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      resume: Option[SessionId[BackendTag.Codex.type]],
      events: OrcaListener
  ): LlmResult[BackendTag.Codex.type] =
    val conv = openConversation(
      prompt = prompt,
      mode = SessionMode.Autonomous,
      config = config,
      workDir = workDir,
      resume = resume,
      // codex `exec resume` rejects `--output-schema`, and autonomous
      // structured calls already wrap the prompt with the schema via
      // DefaultLlmCall's template.
      outputSchema = None
    )
    try Conversations.drainAutonomous(conv, events)
    catch
      case e: OrcaFlowException =>
        throw new OrcaFlowException(s"codex CLI failed: ${e.getMessage}")

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
