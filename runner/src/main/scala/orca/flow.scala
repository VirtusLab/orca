package orca

import orca.backend.Interaction
import orca.events.{
  CostTracker,
  EventDispatcher,
  OrcaEvent,
  OrcaListener,
  PriceList,
  Pricing
}
import orca.llm.{
  ClaudeTool,
  DefaultPrompts,
  LlmTool,
  OpencodeTool,
  PiTool,
  Prompts
}
import orca.progress.{ProgressHeader, ProgressStore, RecoveryCheck}
import orca.tools.opencode.OpencodeLauncher
import orca.runner.{DefaultFlowContext, LoggingListener, OrcaBanner, OrcaLog}
import orca.runner.terminal.TerminalInteraction
import org.slf4j.LoggerFactory
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.tools.OsGitTool
import orca.util.OrcaDebug
import ox.supervised

import scala.util.control.NonFatal

/** Entry point for flow scripts. Takes the parsed CLI args (required) plus any
  * number of overrides, then runs the body, providing the `FlowContext` as a
  * given.
  *
  * ```
  * flow(OrcaArgs(args)):
  *   val plan = claude.resultAs[Plan].autonomous.run(userPrompt)
  *   ...
  * ```
  *
  * Override any tool by passing it as a named argument in the first list:
  *
  * ```
  * flow(
  *   OrcaArgs(args),
  *   git = Some(myGit),
  *   interaction = Some(SlackInteraction(...))
  * ):
  *   ...
  * ```
  *
  * The leading model is named by a `leadModel` selector resolved against the
  * built `FlowContext` (defaulting to `_.claude`): the only way to name a model
  * is the accessor on the context, which isn't in scope at the `flow(...)`
  * argument position, so the selector defers resolution until the context
  * exists. `flow(OrcaArgs(args))` runs against claude; `flow(OrcaArgs(args),
  * _.codex)` against codex, etc. The resolved model becomes `ctx.llm`.
  *
  * WARNING: the selector MUST NOT read `ctx.llm` — `llm` is a lazy val resolved
  * by calling this selector, so `_.llm` would recurse infinitely. Safe
  * selectors read a concrete accessor (`_.claude`, `_.codex`, `_.gemini`,
  * `_.opencode`, `_.pi`) and never `_.llm`.
  *
  * Overrides default to `None` so the runtime can build the default lazily —
  * `TerminalInteraction`, in particular, takes the resolved `workDir` which
  * can't be threaded through a Scala 3 default-arg expression.
  */
def flow(
    args: OrcaArgs,
    leadModel: FlowContext => LlmTool[?] = _.claude,
    workDir: os.Path = os.pwd,
    interaction: Option[Interaction] = None,
    extraListeners: List[OrcaListener] = Nil,
    branchNaming: Option[BranchNamingStrategy] = None,
    progressStore: Option[ProgressStore] = None,
    claude: Option[ClaudeTool] = None,
    opencode: Option[OpencodeTool] = None,
    opencodeLauncher: OpencodeLauncher = OpencodeLauncher.default,
    pi: Option[PiTool] = None,
    git: Option[GitTool] = None,
    gh: Option[GitHubTool] = None,
    fs: Option[FsTool] = None,
    prompts: Prompts = DefaultPrompts,
    pricing: PriceList = Pricing.default
)(body: FlowControl ?=> Unit): Unit =
  // Per-run trace file: captures every stage, prompt, tool/subprocess call and
  // result at DEBUG. Started before anything logs so the whole run is caught;
  // the path is printed by the banner and the detail stays in the file.
  val orcaLog = OrcaLog.start()
  OrcaBanner.print(System.err, orcaLog.file)
  val flowLog = LoggerFactory.getLogger("orca.flow")
  flowLog.info("orca {} starting (workDir={})", OrcaBanner.version, workDir)
  flowLog.info("user prompt: {}", args.userPrompt)
  // A daemon thread or unsupervised fork that throws would otherwise
  // disappear with no diagnostic. Log the message to the console and the
  // stack to the trace file so a silent exit always leaves a trail.
  installUncaughtExceptionHandler()
  // Always tally token usage; print the summary on exit (success or failure)
  // so the user sees what was spent before the process terminates. Callers
  // can still pass their own CostTracker via `extraListeners` for other uses
  // — it'll observe the same events independently.
  val costTracker = new CostTracker(pricing)
  // `try/finally` so the cost summary always lands — even when a fatal
  // throwable (OOM, StackOverflow) escapes the NonFatal catch below.
  // Tokens may have already been spent; the user deserves to see what.
  try
    try
      runFlow(
        args,
        leadModel,
        workDir,
        interaction,
        extraListeners ++ List(costTracker),
        branchNaming,
        progressStore,
        claude,
        opencode,
        opencodeLauncher,
        pi,
        git,
        gh,
        fs,
        prompts
      )(body)
    catch
      // The failure was already surfaced inside the scope (the flow body runs
      // as a top-level stage): the message went to the console, the stack to
      // the trace file. Here we only fail the process — the summary +
      // trace-detach run before `System.exit(1)` skips the outer `finally`.
      case NonFatal(_) =>
        costTracker.printSummary()
        orcaLog.finish()
        System.exit(1)
  finally
    costTracker.printSummary()
    // Detach the trace appender. Idempotent — the error path above already
    // finished it before exiting.
    orcaLog.finish()

/** Exit-free flow lifecycle: builds the interaction/context, runs setup, then
  * runs the body as a top-level stage with disjoint success/failure teardown.
  * Unlike [[flow]], a `NonFatal` failure in `body` is **propagated** (after
  * failure teardown), not turned into a `System.exit` — so the
  * crash→`resetHard`→resume wiring is directly testable end-to-end. [[flow]]
  * wraps this to keep the observable CLI behaviour (cost summary, OrcaLog,
  * `System.exit(1)`).
  *
  * `extraListeners` is the full listener set this run should observe beyond the
  * interaction's own (the CLI wrapper adds its [[CostTracker]] here); a
  * [[LoggingListener]] is always appended.
  */
private[orca] def runFlow(
    args: OrcaArgs,
    leadModel: FlowContext => LlmTool[?],
    workDir: os.Path,
    interaction: Option[Interaction],
    extraListeners: List[OrcaListener],
    branchNaming: Option[BranchNamingStrategy],
    progressStore: Option[ProgressStore],
    claude: Option[ClaudeTool],
    opencode: Option[OpencodeTool],
    opencodeLauncher: OpencodeLauncher,
    pi: Option[PiTool],
    git: Option[GitTool],
    gh: Option[GitHubTool],
    fs: Option[FsTool],
    prompts: Prompts
)(body: FlowControl ?=> Unit): Unit =
  val debug = OrcaDebug.enabled || args.verbose.value
  val flowLog = LoggerFactory.getLogger("orca.flow")
  // Default TerminalInteraction is built inside `supervised:` because its
  // worker is a `forkUser` bound to that scope; close() in the body's
  // `finally` lets the worker drain and exit before the scope joins it.
  supervised:
    val effectiveInteraction = interaction.getOrElse(
      TerminalInteraction.start(workDir = Some(workDir))
    )
    try
      val dispatcher = new EventDispatcher(
        effectiveInteraction.listeners ++ List(
          new LoggingListener
        ) ++ extraListeners
      )
      // Resolve the git tool up-front: the lifecycle's setup (stash, branch
      // checkout, header commit) and teardown (cleanup, restore) run before
      // and after the body, outside any user stage, so they need git
      // directly. The same instance is handed to the context.
      val effectiveGit = git.getOrElse(new OsGitTool(workDir, dispatcher))
      // Order matters (and is delicate): the leading model is now a selector
      // resolved against the context (`ctx.llm`), and branch setup needs the
      // resolved model for branch naming. So the store and the context must
      // exist BEFORE setup runs:
      //   1. Resolve the progress store (pure — no git effect, no model).
      //   2. Build the context (pure construction — backends are created but
      //      no subprocess spawns until the first gated `run`; `ctx.llm` is a
      //      lazy selector resolution).
      //   3. Run branch setup (stash → resume-vs-fresh → checkout → header
      //      commit) using `ctx.llm` for branch naming.
      // Teardown is unchanged (the `bodySucceeded` gate + disjoint
      // success/failure paths below), so CORE's invariants are preserved.
      val store =
        progressStore.getOrElse(
          ProgressStore.default(workDir, args.userPrompt)
        )
      val ctx = DefaultFlowContext.withDefaults(
        userPrompt = args.userPrompt,
        dispatcher = dispatcher,
        workDir = workDir,
        interaction = effectiveInteraction,
        progressStore = store,
        leadModel = leadModel,
        claude = claude,
        opencode = opencode,
        opencodeLauncher = opencodeLauncher,
        pi = pi,
        git = Some(effectiveGit),
        gh = gh,
        fs = fs,
        prompts = prompts
      )
      val setup =
        flowSetup(args, ctx.llm, effectiveGit, branchNaming, store)
      // Rehydrate the client→server session map (R22): the registry is
      // in-memory, so on resume the leading model's mapping is empty. Replay
      // the persisted records into it (after the context + log exist, before
      // the body) so `dispatchFor` resumes the right server thread and the
      // server-id existence probes work. Only the leading model is rehydrated —
      // the common case; multi-tool flows are a known limitation (see report).
      rehydrateSessions(ctx.llm, store)
      // The whole flow body runs as a top-level stage: an otherwise
      // unhandled exception surfaces as a single Error event (the same
      // message a stage failure shows). A nested stage / `fail` marks the
      // exception `alreadyEmitted` once it has reported it, so we don't
      // re-report it here. The stack goes to the trace file only (DEBUG,
      // below the console's WARN threshold); `--verbose` also prints it to
      // stderr.
      //
      // Teardown separation: body-failure and body-success teardowns are
      // completely disjoint. A success-teardown error (e.g. a cosmetic
      // cleanup-commit failure) must NOT trigger the failure teardown
      // (`resetHard`), and must NOT strand the user on the feature branch.
      var bodySucceeded = false
      try
        body(using ctx)
        bodySucceeded = true
      catch
        case NonFatal(e) =>
          val alreadyEmitted = e match
            case fe: OrcaFlowException => fe.alreadyEmitted
            case _                     => false
          if !alreadyEmitted then ctx.emit(OrcaEvent.Error(throwableMessage(e)))
          flowLog.debug("flow aborted", e)
          if debug then e.printStackTrace(System.err)
          flowTeardownFailure(effectiveGit)
          throw e
      if bodySucceeded then flowTeardownSuccess(effectiveGit, setup)
    finally effectiveInteraction.close()

/** Replay the persisted client→server session map (ADR 0018 §2.6, R22) into the
  * leading model's in-memory registry, so a resumed run resumes the right
  * server thread and the server-id existence probes target the right id. Reads
  * every [[orca.progress.SessionRecord]] that carries a `serverId` and
  * registers the mapping via [[orca.llm.LlmTool.registerServerSession]].
  *
  * The type parameter `B` binds the wildcard backend tag from `ctx.llm`
  * (`LlmTool[?]`) so the client/server [[orca.llm.SessionId]]s share its type.
  * Only the leading model is rehydrated (the common case); a flow that drives a
  * second tool's sessions across resume is a known limitation.
  */
private def rehydrateSessions[B <: orca.llm.BackendTag](
    llm: LlmTool[B],
    store: ProgressStore
): Unit =
  for
    log <- store.load().toList
    record <- log.sessions
    serverId <- record.serverId
  do
    llm.registerServerSession(
      orca.llm.SessionId[B](record.id),
      orca.llm.SessionId[B](serverId)
    )

/** Outcome of [[flowSetup]]: the resolved progress store, the feature branch
  * the run is bound to, and the starting branch to restore on success.
  */
private case class FlowSetup(
    store: ProgressStore,
    featureBranch: String,
    startBranch: String
)

/** Bind the run to a branch + progress log before the body runs (ADR 0018
  * §2.4/§2.5). Records the starting branch, snapshots the log file, stashes a
  * dirty tree, then either resumes an existing log or starts fresh (resolve a
  * branch name, create it, write + commit the header). All git/store mutations
  * run with a runtime-minted `InStage` — setup is privileged, predating any
  * user stage.
  *
  * The progress header is **untrusted input** on load (R26: the log is
  * human-visible and pushable), so a resumed run:
  *   - **R20** — snapshots the log file BEFORE `ensureClean` and restores it if
  *     the stash removed it, so the header is always readable.
  *   - **R32** — validates the header before any destructive action (safe refs,
  *     prompt-hash match, no protected feature branch). A parseable-but-invalid
  *     header is a HARD abort (`OrcaFlowException`), not a silent fresh start —
  *     it signals tampering or a mismatch. (An *unparseable* log stays
  *     `store.load() == None` → fresh run; that path is separate.)
  *   - **R30** — cross-checks that the current branch is the one the header
  *     records (the in-place invariant from R3): a log that surfaced on a
  *     branch it does not name (e.g. its feature branch was merged, carrying
  *     the log along) aborts rather than resuming against the wrong branch.
  *
  * On resume `startBranch` is the header's recorded `startingBranch` (the
  * ORIGINAL branch at first run), so a resumed run returns there on success —
  * exactly like a fresh run — not to the re-run's current (feature) branch.
  */
private def flowSetup(
    args: OrcaArgs,
    llm: LlmTool[?],
    git: GitTool,
    branchNaming: Option[BranchNamingStrategy],
    store: ProgressStore
): FlowSetup =
  given InStage = InStage.unsafe
  val startBranch = git.currentBranch()
  // R20: snapshot the log file before the stash, restore it if the stash
  // removed it — so an uncommitted/untracked log is still readable below.
  val snapshot = snapshotLog(store.path)
  val _ = git.ensureClean("orca: starting flow")
  restoreLogIfMissing(store.path, snapshot)
  store.load() match
    case Some(log) =>
      val header = log.header
      // R32: validate the untrusted header before any destructive action.
      RecoveryCheck.validateHeader(header, args.userPrompt) match
        case Left(reason) =>
          throw new OrcaFlowException(
            s"refusing to resume: progress log header failed validation ($reason)"
          )
        case Right(()) => ()
      // R30: only resume IN PLACE. If the log surfaced on a branch it does not
      // name, it was likely carried here by a merge — abort, don't replay.
      val current = git.currentBranch()
      if current != header.branch then
        throw new OrcaFlowException(
          s"progress log for branch '${header.branch}' found while on " +
            s"'$current' — was it merged? aborting rather than resuming " +
            "against the wrong branch"
        )
      // Resume in place: already on header.branch (R3). Return to the ORIGINAL
      // start branch on success, not this feature branch.
      FlowSetup(store, header.branch, header.startingBranch)
    case None =>
      // Fresh run: resolve + create the branch, then commit the header so it is
      // the branch's first commit.
      val strategy =
        branchNaming.getOrElse(BranchNamingStrategy.fromText(args.userPrompt))
      val branch = strategy.resolve(args.userPrompt, llm)
      git.checkoutOrCreate(branch)
      store.writeHeader(
        ProgressHeader(
          startingBranch = startBranch,
          branch = branch,
          promptHash = ProgressStore.hashPrompt(args.userPrompt)
        )
      )
      git.forceAdd(store.path)
      val _ = git.commit("orca: progress log")
      FlowSetup(store, branch, startBranch)

/** Read the bytes of the progress-log file if it exists (R20). Returns `None`
  * when the file is absent — the normal fresh-run case and the case where the
  * log is committed (so the stash can't remove it).
  */
private[orca] def snapshotLog(path: os.Path): Option[Array[Byte]] =
  if os.exists(path) then Some(os.read.bytes(path)) else None

/** Restore the progress-log file from a pre-stash snapshot if the stash removed
  * it (R20), so the header is always readable. A no-op when there was nothing
  * to snapshot or the file still exists.
  */
private[orca] def restoreLogIfMissing(
    path: os.Path,
    snapshot: Option[Array[Byte]]
): Unit =
  snapshot.foreach: bytes =>
    if !os.exists(path) then os.write.over(path, bytes, createFolders = true)

/** Successful teardown (ADR 0018 §2.5): remove the progress-log file in a final
  * commit so a merged branch is clean, then return to the starting branch.
  * Throwaway-branch auto-delete (R5) is deferred.
  *
  * Errors during log removal or the cleanup commit are cosmetic — swallowed so
  * they don't trigger the failure path. The checkout back to `startBranch` is
  * always attempted (in a `finally`) so a cleanup error never strands the user
  * on the feature branch.
  */
private def flowTeardownSuccess(git: GitTool, setup: FlowSetup): Unit =
  try
    // Best-effort: a missing file (already gone) or a failing cleanup commit is
    // cosmetic on an already-successful run, so neither must escape teardown.
    try os.remove(setup.store.path)
    catch
      case _: java.nio.file.NoSuchFileException => ()
      // `add -A` in commit picks up the removal; NothingToCommit (a Left) means it
      // was never committed — harmless. A genuine commit failure is swallowed too:
      // the run already succeeded, and the progress file is untracked on the
      // starting branch we are about to return to.
    try
      val _ = git.commit("orca: remove progress log")
    catch case NonFatal(_) => ()
  finally
    // Always attempt to return to the starting branch, even if cleanup failed.
    git.checkoutOrCreate(setup.startBranch)

/** Failure teardown (ADR 0018 §2.5): discard the failed stage's uncommitted
  * partial edits with `git reset --hard` (which restores the last committed
  * log), and stay on the feature branch so the next run resumes in place.
  */
private def flowTeardownFailure(git: GitTool): Unit =
  git.resetHard()

private def installUncaughtExceptionHandler(): Unit =
  // Idempotent across nested or repeated `flow(...)` calls — we only install
  // our handler if no app-specific one is already in place. The `orca` logger
  // is routed to the trace file only (see `OrcaLog`), so the message goes
  // straight to the console via stderr; the stack follows it into the trace.
  if Thread.getDefaultUncaughtExceptionHandler == null then
    val log = LoggerFactory.getLogger("orca")
    Thread.setDefaultUncaughtExceptionHandler: (thread, throwable) =>
      System.err.println(
        s"[orca] uncaught exception on thread '${thread.getName}': " +
          throwable.getMessage
      )
      log.debug("uncaught exception stack trace", throwable)
