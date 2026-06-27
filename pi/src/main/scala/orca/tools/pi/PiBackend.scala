package orca.tools.pi

import orca.events.OrcaListener
import orca.agents.{BackendTag, AgentConfig, SessionId}
import orca.backend.{
  Conversation,
  Conversations,
  Dispatch,
  AgentBackend,
  AgentResult,
  SessionMode,
  SessionRegistry,
  SubprocessSpawn,
  SystemPromptComposer
}
import orca.subprocess.CliRunner

import scala.collection.mutable.ListBuffer

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
private[orca] class PiBackend(cli: CliRunner)
    extends AgentBackend[BackendTag.Pi.type]:

  // Pi persists each session in a directory; one dir per Orca session id gives
  // caller-stable continuity. The registry tracks fresh-vs-resume and is
  // committed only *after* a successful turn, so a retried open-failure starts
  // fresh rather than `--continue`-ing a dir Pi never created.
  private val sessionsBase: os.Path =
    os.temp.dir(prefix = "orca-pi-sessions-", deleteOnExit = true)
  private val sessions = new SessionRegistry.ClaimedOnce[BackendTag.Pi.type]

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Pi.type],
      config: AgentConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): AgentResult[BackendTag.Pi.type] =
    val conv = openConversation(
      prompt = prompt,
      mode = SessionMode.Autonomous,
      session = session,
      config = config,
      workDir = workDir,
      outputSchema = outputSchema
    )
    Conversations
      .drainAndCommit("pi", conv, session, sessions, events)
      .copy(sessionId = session)

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Pi.type],
      displayPrompt: String,
      config: AgentConfig,
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

  /** Marks an interactive session resumable once its turn has succeeded — the
    * framework calls this after driving the returned conversation to
    * completion.
    */
  override def registerSession(
      client: SessionId[BackendTag.Pi.type],
      serverSession: SessionId[BackendTag.Pi.type]
  ): Unit = sessions.commitSuccess(client, serverSession)
  // No `serverFor` override: pi's sessions live in a `deleteOnExit` temp dir
  // (gone across runs), so there is nothing durable to persist or rehydrate — pi
  // always re-seeds (ADR 0018 §2.6). The default `None` keeps pi unaffected by
  // the persist/rehydrate path.

  private def openConversation(
      prompt: String,
      mode: SessionMode,
      session: SessionId[BackendTag.Pi.type],
      config: AgentConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): PiConversation =
    // Temp files (ask-user extension, system prompt) Pi reads for the whole
    // turn. Ownership passes to the conversation once it's constructed — it
    // closes them in `onFinalize` when the turn ends; `closeResources` here is
    // the backstop for a failure before that point. Closes are idempotent
    // (`closeQuietly` + `os.remove.all`); the temp dirs are also `deleteOnExit`,
    // so a hard JVM kill mid-turn still reclaims them.
    val resources = ListBuffer.empty[AutoCloseable]
    def register[A <: AutoCloseable](resource: A): A =
      resources += resource
      resource

    val (displayPrompt, askUserExtension, extraHint) = mode match
      case SessionMode.Autonomous =>
        ("", None, None)
      case SessionMode.Interactive(p) =>
        val extension = register(PiAskUserExtension.allocate())
        (p, Some(extension), Some(PiAskUserExtension.Hint))

    // `resources` is accumulated above (and as the argv is built); SubprocessSpawn
    // reads it (by-name) only on a spawn/build failure to release it.
    SubprocessSpawn.open("pi RPC", resources.toList) {
      val systemPromptFile = writeSystemPromptIfPresent(config, extraHint)
        .map(register)

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
        resources = resources.toList
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
