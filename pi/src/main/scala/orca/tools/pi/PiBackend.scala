package orca.tools.pi

import orca.events.OrcaListener
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  SessionId,
  StructuredOutputMode,
  ToolSet
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
  SubprocessSpawn,
  SystemPromptComposer
}
import orca.subprocess.CliRunner

import ox.Ox

/** Pi backend driven through `pi --mode rpc` JSONL over stdio.
  *
  * Pi exposes no HTTP server and its SDK is Node-only, so a subprocess is the
  * only way to embed it; `--mode rpc` gives the bidirectional channel needed
  * for `ask_user` extension-UI replies within a turn.
  *
  * Lifecycle is per-call: each call spawns its own process, sends one `prompt`,
  * reads to `agent_end`, then exits. Context carries across calls through a
  * per-session `--session-dir` that Pi seeds on the first turn and `--continue`
  * resumes on later ones.
  */
private[orca] class PiBackend(
    cli: CliRunner,
    /** Working directory every spawn runs in. The `os.pwd` default serves test
      * construction; the runtime passes the flow's real `workDir`.
      */
    override val workDir: os.Path = os.pwd
) extends AgentBackend[BackendTag.Pi.type]:

  // One session dir per Orca session id gives caller-stable continuity.
  // Fresh-vs-resume is committed only after a successful turn, so a retried
  // open-failure starts fresh rather than `--continue`-ing a dir Pi never made.
  private val sessionsBase: os.Path =
    os.temp.dir(prefix = "orca-pi-sessions-", deleteOnExit = true)

  /** Ephemeral: Pi's sessions live in a `deleteOnExit` temp dir, so
    * fresh-vs-resume is tracked only within a live process
    * ([[IdScheme.ClientClaimed]]) with nothing durable to persist or probe — pi
    * always re-seeds across runs (ADR 0018 §2.6).
    */
  val sessions: SessionSupport[BackendTag.Pi.type] =
    SessionSupport.ephemeral(IdScheme.ClientClaimed)

  val tag: BackendTag.Pi.type = BackendTag.Pi

  override def enforcement(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): Enforcement =
    PiArgs.enforcement(tools, autoApprove)

  /** Pi has no native structured-output / JSON-schema flag (see
    * [[PiConversation]]) — the reply text is the JSON value.
    */
  override def structuredOutputMode: StructuredOutputMode =
    StructuredOutputMode.RawText

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Pi.type],
      config: AgentConfig,
      events: OrcaListener,
      outputSchema: Option[String]
  ): AgentResult[BackendTag.Pi.type] =
    Conversations.runAutonomous(session, sessions, events):
      openConversation(
        prompt = prompt,
        mode = ConversationMode.Autonomous,
        session = session,
        config = config,
        outputSchema = outputSchema
      )

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Pi.type],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.Pi.type] =
    openConversation(
      prompt = prompt,
      mode = ConversationMode.Interactive(displayPrompt),
      session = session,
      config = config,
      outputSchema = outputSchema
    )

  private def openConversation(
      prompt: String,
      mode: ConversationMode,
      session: SessionId[BackendTag.Pi.type],
      config: AgentConfig,
      outputSchema: Option[String]
  ): PiConversation =
    // Temp files (ask-user extension, system prompt) Pi reads for the whole
    // turn. Ownership passes to the conversation, which closes them in
    // `onFinalize`; `SubprocessSpawn.open`'s failure path is the backstop for a
    // failure before construction. Closes are idempotent and the dirs are
    // `deleteOnExit`, so a hard kill mid-turn still reclaims them.
    val displayPrompt = mode.displayPrompt
    val extraHint = Option.when(mode.isInteractive)(PiAskUserExtension.Hint)

    // Write the system prompt file before allocating any resource, so a
    // temp-write failure can't leak the ask-user extension: with nothing
    // allocated yet, there's nothing to tear down.
    val systemPromptFile = writeSystemPromptIfPresent(config, extraHint)

    val askUserExtension =
      Option.when(mode.isInteractive)(PiAskUserExtension.allocate())

    val resources: List[AutoCloseable] =
      askUserExtension.toList ++ systemPromptFile.toList

    SubprocessSpawn.open("pi RPC", resources) {
      val resume = sessions.dispatchFor(session) match
        case Dispatch.Resume(_) => true
        case Dispatch.Fresh(_)  => false
      val args = PiArgs.rpc(
        sessionDir = sessionsBase / SessionId.value(session),
        resume = resume,
        config = config,
        systemPromptFile = systemPromptFile.map(_.file),
        askUserExtension = askUserExtension.map(_.file)
      )
      cli.spawnPiped(args, cwd = workDir, pipeStderr = true)
    } { process =>
      val conversation = new PiConversation(
        process = process,
        clientSession = session,
        initialPrompt = displayPrompt,
        outputSchema = outputSchema,
        askUserEnabled = askUserExtension.isDefined,
        resources = resources
      )
      conversation.sendPrompt(prompt)
      conversation
    }

  private def writeSystemPromptIfPresent(
      config: AgentConfig,
      extraHint: Option[String]
  ): Option[TempFileResource] =
    SystemPromptComposer
      .combine(config, extraHint)
      .map: text =>
        val dir =
          os.temp.dir(prefix = "orca-pi-system-prompt-", deleteOnExit = true)
        val file = dir / "system-prompt.md"
        os.write(file, text)
        TempFileResource(dir, file)

  private case class TempFileResource(dir: os.Path, file: os.Path)
      extends AutoCloseable:
    def close(): Unit = os.remove.all(dir)
