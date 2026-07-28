package orca.tools.pi

import orca.OrcaDir
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

import java.time.Instant
import scala.util.control.NonFatal

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
    override val workDir: os.Path = os.pwd,
    /** Wall clock for the session-cache retention cutoff (see
      * [[PiBackend.Retention]]).
      */
    clock: () => Instant = () => Instant.now()
) extends AgentBackend[BackendTag.Pi.type]:

  // One session dir per Orca session id gives caller-stable continuity. Within
  // a run, fresh-vs-resume is committed only after a successful turn, so a
  // retried open-failure starts fresh. Across runs the mapping is rehydrated
  // from the progress log and `--continue` is dispatched against the recorded
  // id; whether that dir still holds a transcript is what `hasTranscript`
  // answers, and the runtime re-seeds when it says no.
  //
  // Passive path: naming the dir must not create `.orca` (under the `os.pwd`
  // default that would be the wrong tree, and the probe is a read path) — the
  // spawn path ensures it.
  private val sessionsBase: os.Path = OrcaDir.piSessionsPath(workDir)

  /** Durable: each session's transcript lives under
    * `.orca/cache/pi-sessions/<session id>/` and outlives the run, so the
    * claimed id ([[IdScheme.ClientClaimed]]) is worth persisting and existence
    * is a best-effort on-disk probe (ADR 0018 §2.6).
    */
  val sessions: SessionSupport[BackendTag.Pi.type] =
    SessionSupport.durable(IdScheme.ClientClaimed, hasTranscript)

  /** Does Pi's session dir for `id` hold a transcript to `--continue` from, and
    * is it young enough to still be there after the next prune? Applying the
    * retention cutoff here too means a dir another process is about to prune
    * reports absent now, rather than after the runtime committed to resuming
    * it.
    *
    * At least one `*.jsonl`, not exactly one: a first turn that failed after Pi
    * seeded the dir is retried fresh and seeds a second file. `--continue`
    * picks the most recent, which is the one the retry wrote.
    */
  private def hasTranscript(id: String): Boolean =
    val dir = sessionsBase / id
    os.isDir(dir) && touchedSince(dir, cutoff()) &&
    os.list.stream(dir).exists(_.last.endsWith(".jsonl"))

  /** The session dir root, created on first use. `lazy` because naming the dir
    * must stay effect-free (see [[sessionsBase]]) — only spawning creates.
    */
  private lazy val ensuredSessionsBase: os.Path =
    OrcaDir.ensurePiSessions(workDir)

  private def cutoff(): Instant = clock().minus(PiBackend.Retention)

  /** Delete session dirs untouched since `cutoff()`. Pi prunes its own default
    * session store but not the dir orca points it at, so without this the cache
    * grows by one dir per session forever. Run by [[PiBackend.create]] before
    * the backend is handed out, so no probe can call a doomed dir resumable.
    *
    * Fully best-effort — the listing and each candidate are guarded separately
    * — because a cache that can't be pruned (a race with another run, a
    * read-only dir) must not fail the backend it belongs to.
    */
  private def pruneSessionCache(): Unit =
    if os.exists(sessionsBase) then
      val stale = cutoff()
      try
        // Sorted (`os.list`, not `os.list.stream`): one small array of session
        // dirs, in exchange for a prune order that doesn't depend on readdir.
        os.list(sessionsBase)
          .foreach: dir =>
            try
              if os.isDir(dir) && !touchedSince(dir, stale) then
                os.remove.all(dir)
            catch case NonFatal(_) => ()
      catch case NonFatal(_) => ()

  /** Was `dir` — or any file in it — modified at or after `cutoff`? Its own
    * mtime is not enough: appending to an existing transcript doesn't bump the
    * containing directory's mtime, so a long-lived chat would otherwise look
    * untouched since the turn that created it. Checked dir-first so a fresh dir
    * costs one stat and a stale scan stops at the first fresh file.
    */
  private def touchedSince(dir: os.Path, cutoff: Instant): Boolean =
    def touched(p: os.Path) =
      !Instant.ofEpochMilli(os.mtime(p)).isBefore(cutoff)
    touched(dir) || os.list.stream(dir).exists(touched)

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
        // The one place the session dir is created: Pi seeds its transcript
        // inside `<base>/<session id>`, so the base must exist by spawn time.
        sessionDir = ensuredSessionsBase / SessionId.value(session),
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

private[pi] object PiBackend:
  /** How long an untouched session dir survives in `.orca/cache/pi-sessions`,
    * matching claude's own transcript retention. A pruned session is not a lost
    * turn: the probe then reports absence and the runtime re-seeds.
    */
  private[pi] val Retention: java.time.Duration = java.time.Duration.ofDays(30)

  /** The runtime's door: builds a backend and prunes its session cache before
    * handing it out, so no probe can call a dir the next prune would take
    * resumable. The bare constructor stays effect-free, for
    * construct-to-inspect uses (enforcement tables, arg wiring).
    */
  private[pi] def create(
      cli: CliRunner,
      workDir: os.Path,
      clock: () => Instant = () => Instant.now()
  ): PiBackend =
    val backend = new PiBackend(cli, workDir, clock)
    backend.pruneSessionCache()
    backend
