package orca.backend.pi

import orca.OrcaDir
import orca.effect.FileSystem
import orca.agents.{
  Model,
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
  AskUserChannel,
  LiveTurn,
  TurnRequest,
  AgentBackend,
  IdScheme,
  SessionSupport,
  SubprocessSpawn,
  SystemPromptComposer
}
import orca.subprocess.CliRunner
import orca.safe.prelude.given

import ox.{Ox, ResourceScope}

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
    fs: FileSystem,
    cli: CliRunner,
    /** [[PiBackend.transcriptProbe]] in production; given [[workDir]]. */
    transcriptExists: (os.Path, String) -> Boolean,
    /** Working directory every spawn runs in, and where the probe looks. */
    override val workDir: os.Path
) extends AgentBackend[BackendTag.Pi.type]:

  // One session dir per Orca session id gives caller-stable continuity. The
  // claimed id is committed only after a successful turn, so both a retried
  // open-failure and a resumed run resolve fresh-vs-resume from the dir itself:
  // `--continue` whenever `transcriptExists` finds a transcript pi already seeded
  // there, and a fresh seed when it says no.

  /** Durable: each session's transcript lives under
    * `.orca/cache/pi-sessions/<session id>/` and outlives the attempt, so the
    * claimed id ([[IdScheme.ClientClaimed]]) is worth persisting and existence
    * is a best-effort on-disk probe (ADR 0018 §2.6).
    */
  val sessions: SessionSupport[BackendTag.Pi.type] =
    SessionSupport.durable(
      IdScheme.ClientClaimed,
      id => transcriptExists(workDir, id)
    )

  val tag: BackendTag.Pi.type = BackendTag.Pi

  export PiArgs.enforcementCell

  /** Pi has no native structured-output / JSON-schema flag (see [[PiTurn]]) —
    * the reply text is the JSON value.
    */
  override def structuredOutputMode: StructuredOutputMode =
    StructuredOutputMode.RawText

  /** Pi has no named tiers, so `cheap` keeps the agent's own model. */
  def cheapModel(leading: Option[Model]): Option[Model] = None

  override protected[orca] def open(
      turn: TurnRequest[BackendTag.Pi.type]
  )(using Ox): LiveTurn[BackendTag.Pi.type] =
    import turn.*
    val extraHint = Option.when(mode.isInteractive)(PiAskUserExtension.Hint)
    val systemPromptFile = writeSystemPrompt(config, extraHint)
    val askUserExtension =
      Option.when(mode.isInteractive)(PiAskUserExtension.write(fs))

    SubprocessSpawn.open("pi RPC", events) {
      val args = PiArgs.rpc(
        // The one place the session dir is created: Pi seeds its transcript
        // inside `<base>/<session id>`, so the base must exist by spawn time.
        // Ensured per spawn, so a cache deleted mid-run is recreated.
        sessionDir =
          OrcaDir(fs).ensurePiSessions(workDir) / SessionId.value(session),
        dispatch = dispatch.asTurnDispatch,
        config = config,
        systemPromptFile = Some(systemPromptFile),
        askUserExtension = askUserExtension
      )
      cli.spawnPiped(args, cwd = workDir)
    } { process =>
      PiTurn(
        process = process,
        clientSession = session,
        prompt = prompt,
        openingPrompt = mode.openingPrompt,
        outputSchema = outputSchema,
        askUser = askUserExtension.fold(AskUserChannel.Unavailable)(_ =>
          AskUserChannel.Native
        )
      )
    }

  private def writeSystemPrompt(
      config: AgentConfig,
      extraHint: Option[String]
  )(using ResourceScope): os.Path =
    val file =
      fs.tempDir("orca-pi-system-prompt-") / "system-prompt.md"
    fs.writeNew(file, SystemPromptComposer.combine(config, extraHint))
    file

private[orca] object PiBackend:
  /** The runtime's door: prunes the session cache under `workDir`, then builds
    * a backend, so no probe can call a dir the next prune would take resumable.
    */
  private[orca] def create(
      fs: FileSystem,
      cli: CliRunner,
      transcriptExists: (os.Path, String) -> Boolean,
      workDir: os.Path,
      now: Instant
  ): PiBackend^{fs} =
    PiSessionStore(fs).prune(workDir, now)
    new PiBackend(fs, cli, transcriptExists, workDir)

  /** Whether `id`'s session dir under `workDir` holds a transcript pi resumes.
    * A read path: it never creates `.orca`, only spawning does.
    */
  def transcriptProbe(
      fs: FileSystem,
      clock: () -> Instant
  ): (os.Path, String) ->{fs} Boolean =
    val store = PiSessionStore(fs)
    (workDir, id) =>
      PiSessionStore
        .dirFor(workDir, id)
        .exists(store.resumable(_, workDir, clock()))

  /** A backend built only to be inspected (enforcement tables, arg wiring),
    * never spawned or probed: no prune, no disk effects.
    */
  private[orca] def forInspection(
      fs: FileSystem,
      cli: CliRunner,
      workDir: os.Path
  ): PiBackend^{fs} =
    new PiBackend(fs, cli, (_, _) => false, workDir)
