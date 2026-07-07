package orca.tools.pi

import orca.events.OrcaListener
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  SessionId,
  ToolSet
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
  SubprocessSpawn,
  SystemPromptComposer
}
import orca.subprocess.CliRunner

import ox.Ox

/** Pi backend driven through `pi --mode rpc` JSONL over stdio.
  *
  * Pi exposes no HTTP server and its in-process SDK is Node-only, so a
  * subprocess is the only way to embed it from the JVM; `--mode rpc` is the
  * bidirectional channel (needed within a turn for `ask_user` extension-UI
  * replies).
  *
  * Lifecycle is deliberately per-call: each Orca call spawns its own `pi --mode
  * rpc` process, sends one `prompt`, reads to `agent_end`, then lets the
  * process exit. Context carries across calls through a per-session
  * `--session-dir` (one dir per Orca session id) that Pi creates on the first
  * turn and `--continue` resumes on later turns — rather than a long-lived
  * process.
  */
private[orca] class PiBackend(
    cli: CliRunner,
    /** Fixed at construction; every spawn (`openConversation`) runs in this
      * directory. The `os.pwd` default serves bare/test construction; the
      * runtime (`PiAgents.default`) passes the flow's real `workDir`.
      */
    override val workDir: os.Path = os.pwd
) extends AgentBackend[BackendTag.Pi.type]:

  // Pi persists each session in a directory; one dir per Orca session id gives
  // caller-stable continuity. The registry tracks fresh-vs-resume and is
  // committed only *after* a successful turn, so a retried open-failure starts
  // fresh rather than `--continue`-ing a dir Pi never created.
  private val sessionsBase: os.Path =
    os.temp.dir(prefix = "orca-pi-sessions-", deleteOnExit = true)

  /** Pi's sessions live in a `deleteOnExit` temp dir (gone across runs), so it
    * is [[SessionSupport.Ephemeral]]: the wrapped
    * [[SessionRegistry.ClaimedOnce]] tracks fresh-vs-resume within a live
    * process, but there is nothing durable to persist, rehydrate, or probe — pi
    * always re-seeds across runs (ADR 0018 §2.6). The `Ephemeral` case says
    * this structurally, so `resumeWireId`/`exists` report absence without a
    * per-backend override. The registry is encapsulated; the spawn/commit paths
    * go through `sessions.dispatchFor` / `Conversations.runAutonomous(session,
    * sessions, …)`.
    */
  val sessions: SessionSupport[BackendTag.Pi.type] =
    SessionSupport.Ephemeral(
      new SessionRegistry.ClaimedOnce[BackendTag.Pi.type]
    )

  val tag: BackendTag.Pi.type = BackendTag.Pi

  override def enforcement(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): Enforcement =
    PiArgs.enforcement(tools, autoApprove)

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Pi.type],
      config: AgentConfig,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): AgentResult[BackendTag.Pi.type] =
    Conversations.runAutonomous(session, sessions, events):
      openConversation(
        prompt = prompt,
        mode = SessionMode.Autonomous,
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
      mode = SessionMode.Interactive(displayPrompt),
      session = session,
      config = config,
      outputSchema = outputSchema
    )

  private def openConversation(
      prompt: String,
      mode: SessionMode,
      session: SessionId[BackendTag.Pi.type],
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): PiConversation =
    // Temp files (ask-user extension, system prompt) Pi reads for the whole
    // turn. Ownership passes to the conversation once it's constructed — it
    // closes them in `onFinalize` when the turn ends; `SubprocessSpawn.open`'s
    // failure path is the backstop for a failure before that point. Closes are
    // idempotent (`closeQuietly` + `os.remove.all`); the temp dirs are also
    // `deleteOnExit`, so a hard JVM kill mid-turn still reclaims them. Both
    // files are allocated up front (before `open`) so `resources` is a plain
    // immutable list.
    val (displayPrompt, extraHint) = mode match
      case SessionMode.Autonomous     => ("", None)
      case SessionMode.Interactive(p) => (p, Some(PiAskUserExtension.Hint))

    // Write the system prompt file FIRST — before ANY resource is allocated —
    // so a temp-write failure (e.g. disk full) can't leak the ask-user
    // extension resource that `PiAskUserExtension.allocate()` would spin up:
    // with nothing allocated yet, there's nothing to tear down. `Hint` is a
    // static constant, so knowing it doesn't require the allocation.
    val systemPromptFile = writeSystemPromptIfPresent(config, extraHint)

    val askUserExtension = mode match
      case SessionMode.Autonomous     => None
      case SessionMode.Interactive(_) => Some(PiAskUserExtension.allocate())

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
