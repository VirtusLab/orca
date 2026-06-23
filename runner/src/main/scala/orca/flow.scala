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
import orca.progress.{ProgressHeader, ProgressStore}
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
  * Overrides default to `None` so the runtime can build the default lazily —
  * `TerminalInteraction`, in particular, takes the resolved `workDir` which
  * can't be threaded through a Scala 3 default-arg expression.
  */
def flow(
    args: OrcaArgs,
    llm: LlmTool[?],
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
  val debug = OrcaDebug.enabled || args.verbose.value
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
  // Default TerminalInteraction is built inside `supervised:` because its
  // worker is a `forkUser` bound to that scope; close() in the body's
  // `finally` lets the worker drain and exit before the scope joins it.
  try
    try
      supervised:
        val effectiveInteraction = interaction.getOrElse(
          TerminalInteraction.start(workDir = Some(workDir))
        )
        try
          val dispatcher = new EventDispatcher(
            effectiveInteraction.listeners ++ List(
              costTracker,
              new LoggingListener
            ) ++ extraListeners
          )
          // Resolve the git tool up-front: the lifecycle's setup (stash, branch
          // checkout, header commit) and teardown (cleanup, restore) run before
          // and after the body, outside any user stage, so they need git
          // directly. The same instance is handed to the context.
          val effectiveGit = git.getOrElse(new OsGitTool(workDir, dispatcher))
          val setup = flowSetup(
            args,
            llm,
            workDir,
            effectiveGit,
            branchNaming,
            progressStore
          )
          val ctx = DefaultFlowContext.withDefaults(
            userPrompt = args.userPrompt,
            dispatcher = dispatcher,
            workDir = workDir,
            interaction = effectiveInteraction,
            progressStore = setup.store,
            claude = claude,
            opencode = opencode,
            opencodeLauncher = opencodeLauncher,
            pi = pi,
            git = Some(effectiveGit),
            gh = gh,
            fs = fs,
            prompts = prompts
          )
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
              if !alreadyEmitted then
                ctx.emit(OrcaEvent.Error(throwableMessage(e)))
              flowLog.debug("flow aborted", e)
              if debug then e.printStackTrace(System.err)
              flowTeardownFailure(effectiveGit)
              throw e
          if bodySucceeded then flowTeardownSuccess(effectiveGit, setup)
        finally effectiveInteraction.close()
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

/** Outcome of [[flowSetup]]: the resolved progress store, the feature branch
  * the run is bound to, and the starting branch to restore on success.
  */
private case class FlowSetup(
    store: ProgressStore,
    featureBranch: String,
    startBranch: String
)

/** Bind the run to a branch + progress log before the body runs (ADR 0018
  * §2.5). Records the starting branch, stashes a dirty tree, then either
  * resumes an existing log (checkout its recorded branch) or starts fresh
  * (resolve a branch name, create it, write + commit the header). All git/store
  * mutations run with a runtime-minted `InStage` — setup is privileged,
  * predating any user stage.
  *
  * Header *validation* (R30/R32) and prompt-shortening branch naming are
  * deferred (ADR 0018 §2.5 OUT); the default strategy slugs the prompt.
  */
private def flowSetup(
    args: OrcaArgs,
    llm: LlmTool[?],
    workDir: os.Path,
    git: GitTool,
    branchNaming: Option[BranchNamingStrategy],
    progressStore: Option[ProgressStore]
): FlowSetup =
  given InStage = InStage.unsafe
  val startBranch = git.currentBranch()
  val _ = git.ensureClean("orca: starting flow")
  val store =
    progressStore.getOrElse(ProgressStore.default(workDir, args.userPrompt))
  store.load() match
    case Some(log) =>
      // Resume: the branch name lives in the committed header.
      git.checkoutOrCreate(log.header.branch)
      FlowSetup(store, log.header.branch, startBranch)
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
