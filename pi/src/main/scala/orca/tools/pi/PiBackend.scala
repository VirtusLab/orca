package orca.tools.pi

import orca.events.OrcaListener
import orca.llm.{BackendTag, LlmConfig, SessionId}
import orca.{AgentTurnFailed, OrcaFlowException}
import orca.backend.{
  Conversation,
  Conversations,
  LlmBackend,
  LlmResult,
  SessionMode,
  SystemPromptComposer
}
import orca.subprocess.CliRunner

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

/** Pi backend driven through `pi --mode rpc` JSONL over stdio. Each Orca call
  * owns one Pi RPC subprocess; Pi's persisted `--session` threads context
  * across calls using the caller-supplied Orca session id.
  */
private[orca] class PiBackend(cli: CliRunner)
    extends LlmBackend[BackendTag.Pi.type]:

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Pi.type],
      config: LlmConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): LlmResult[BackendTag.Pi.type] =
    val conv = openConversation(
      prompt = prompt,
      mode = SessionMode.Autonomous,
      session = session,
      config = config,
      workDir = workDir,
      outputSchema = outputSchema
    )
    try
      val result = Conversations.drainAutonomous(conv, events)
      result.copy(sessionId = session)
    catch
      case e: AgentTurnFailed => throw e
      case e: OrcaFlowException =>
        throw new OrcaFlowException(s"pi CLI failed: ${e.getMessage}")

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Pi.type],
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.Pi.type] =
    openConversation(
      prompt = prompt,
      mode = SessionMode.Interactive(displayPrompt),
      session = session,
      config = config,
      workDir = workDir,
      outputSchema = outputSchema
    )

  private def openConversation(
      prompt: String,
      mode: SessionMode,
      session: SessionId[BackendTag.Pi.type],
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): PiConversation =
    // Temp files (ask-user extension, system prompt) Pi reads for the whole
    // turn. Ownership passes to the conversation once it's constructed — it
    // closes them in `onFinalize` when the turn ends; `closeResources` here is
    // the backstop for a failure before that point. All closes are idempotent
    // (`closeQuietly` + `os.remove.all`), so the paths can't leak or double-fail.
    val resources = ListBuffer.empty[AutoCloseable]
    def register[A <: AutoCloseable](resource: A): A =
      resources += resource
      resource

    def closeResources(): Unit = resources.reverseIterator.foreach(closeQuietly)

    try
      val (displayPrompt, askUserExtension, extraHint) = mode match
        case SessionMode.Autonomous =>
          ("", None, None)
        case SessionMode.Interactive(p) =>
          val extension = register(PiAskUserExtension.allocate())
          (p, Some(extension), Some(PiAskUserExtension.Hint))

      val systemPromptFile = writeSystemPromptIfPresent(config, extraHint)
        .map(register)

      val args = PiArgs.rpc(
        session = session,
        config = config,
        systemPromptFile = systemPromptFile.map(_.file),
        askUserExtension = askUserExtension.map(_.file)
      )
      val process = cli.spawnPiped(args, cwd = workDir, pipeStderr = true)
      try
        val conversation = new PiConversation(
          process = process,
          clientSession = session,
          initialPrompt = displayPrompt,
          outputSchema = outputSchema,
          askUserEnabled = askUserExtension.isDefined,
          resources = resources.toList
        )
        conversation.sendPrompt(prompt)
        conversation
      catch
        case e: Exception =>
          process.sendSigInt()
          closeResources()
          throw OrcaFlowException(
            s"Failed to open pi RPC session: ${e.getMessage}"
          )
    catch
      case NonFatal(e) =>
        closeResources()
        throw e

  private def writeSystemPromptIfPresent(
      config: LlmConfig,
      extraHint: Option[String]
  ): Option[TempFileResource] =
    SystemPromptComposer
      .combine(config, extraHint)
      .map: text =>
        val dir =
          os.temp.dir(prefix = "orca-pi-system-prompt-", deleteOnExit = false)
        val file = dir / "system-prompt.md"
        os.write(file, text)
        TempFileResource(dir, file)

  private case class TempFileResource(dir: os.Path, file: os.Path)
      extends AutoCloseable:
    def close(): Unit = os.remove.all(dir)

  private def closeQuietly(resource: AutoCloseable): Unit =
    try resource.close()
    catch case NonFatal(_) => ()
