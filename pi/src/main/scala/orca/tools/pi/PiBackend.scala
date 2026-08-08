package orca.tools.pi

import orca.OrcaDir
import orca.events.OrcaListener
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  EnforcementCell,
  SessionId,
  StructuredOutputMode,
  ToolSet,
  TurnDispatch
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

import java.time.Instant

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
private[orca] class PiBackend private[pi] (
    cli: CliRunner,
    /** Working directory every spawn runs in. The `os.pwd` default serves test
      * construction; the runtime passes the flow's real `workDir`.
      */
    override val workDir: os.Path = os.pwd,
    /** Wall clock for the session-cache retention cutoff (see
      * [[PiSessionStore.Retention]]).
      */
    clock: () => Instant = () => Instant.now()
) extends AgentBackend[BackendTag.Pi.type]:

  // One session dir per Orca session id gives caller-stable continuity. Within
  // a run, fresh-vs-resume is committed only after a successful turn, so a
  // retried open-failure starts fresh. Across runs the mapping is rehydrated
  // from the progress log and `--continue` is dispatched against the recorded
  // id; whether that dir is still resumable is what `hasTranscript` answers,
  // and the runtime re-seeds when it says no.

  /** Durable: each session's transcript lives under
    * `.orca/cache/pi-sessions/<session id>/` and outlives the run, so the
    * claimed id ([[IdScheme.ClientClaimed]]) is worth persisting and existence
    * is a best-effort on-disk probe (ADR 0018 §2.6).
    */
  val sessions: SessionSupport[BackendTag.Pi.type] =
    SessionSupport.durable(IdScheme.ClientClaimed, hasTranscript)

  // A read path: probing must not create `.orca` (under the `os.pwd` default
  // that would be the wrong tree) — only the spawn path creates.
  private def hasTranscript(id: String): Boolean =
    PiSessionStore
      .dirFor(workDir, id)
      .exists(dir => PiSessionStore.resumable(dir, workDir, clock()))

  val tag: BackendTag.Pi.type = BackendTag.Pi

  export PiArgs.enforcementCell

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
    Conversations.runAutonomous(session, sessions, config.autoApprove, events):
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
    val systemPromptFile = writeSystemPrompt(config, extraHint)

    val askUserExtension =
      Option.when(mode.isInteractive)(PiAskUserExtension.allocate())

    val resources: List[AutoCloseable] =
      askUserExtension.toList ++ List(systemPromptFile)

    SubprocessSpawn.open("pi RPC", resources) {
      val resume = sessions.dispatchFor(session) match
        case Dispatch.Resume(_) => true
        case Dispatch.Fresh(_)  => false
      val args = PiArgs.rpc(
        // The one place the session dir is created: Pi seeds its transcript
        // inside `<base>/<session id>`, so the base must exist by spawn time.
        // Ensured per spawn, so a cache deleted mid-run is recreated.
        sessionDir =
          OrcaDir.ensurePiSessions(workDir) / SessionId.value(session),
        resume = resume,
        config = config,
        systemPromptFile = Some(systemPromptFile.file),
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

  private def writeSystemPrompt(
      config: AgentConfig,
      extraHint: Option[String]
  ): TempFileResource =
    val dir =
      os.temp.dir(prefix = "orca-pi-system-prompt-", deleteOnExit = true)
    val file = dir / "system-prompt.md"
    os.write(file, SystemPromptComposer.combine(config, extraHint))
    TempFileResource(dir, file)

  private case class TempFileResource(dir: os.Path, file: os.Path)
      extends AutoCloseable:
    def close(): Unit = os.remove.all(dir)

private[orca] object PiBackend:
  /** The runtime's door: builds a backend and prunes its session cache before
    * handing it out, so no probe can call a dir the next prune would take
    * resumable. The bare constructor stays effect-free because constructing
    * with the `os.pwd` default must not prune the repo's own cache — that's
    * what [[forInspection]] exists for.
    */
  private[orca] def create(
      cli: CliRunner,
      workDir: os.Path,
      clock: () => Instant = () => Instant.now()
  ): PiBackend =
    val backend = new PiBackend(cli, workDir, clock)
    PiSessionStore.prune(workDir, clock())
    backend

  /** A backend built only to be inspected (enforcement tables, arg wiring),
    * never spawned or probed: no workDir, no prune, no disk effects.
    */
  private[orca] def forInspection(cli: CliRunner): PiBackend =
    new PiBackend(cli)
