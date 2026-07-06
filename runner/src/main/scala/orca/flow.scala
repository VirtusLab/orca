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
import orca.agents.{
  Agent,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  DefaultPrompts,
  GeminiAgent,
  OpencodeAgent,
  PiAgent,
  Prompts
}
import orca.progress.ProgressStore
import orca.tools.opencode.OpencodeLauncher
import orca.runner.{
  DefaultFlowContext,
  FlowLifecycle,
  LoggingListener,
  OrcaBanner,
  OrcaLog
}
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
  * flow(OrcaArgs(args), _.claude):
  *   val plan = agent.resultAs[Plan].autonomous.run(userPrompt)
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
  * The leading agent is named by a required `agent` selector resolved against
  * the built `FlowContext`: the only way to name an agent is the accessor on
  * the context, which isn't in scope at the `flow(...)` argument position, so
  * the selector defers resolution until the context exists.
  * `flow(OrcaArgs(args), _.claude)` runs against claude; `flow(OrcaArgs(args),
  * _.codex)` against codex, etc. Inside the body, reference the resolved lead
  * via the backend-agnostic [[agent]] accessor (not a concrete
  * `claude`/`codex`) so switching the selector switches the whole flow.
  *
  * `B` is the leading agent's backend tag, inferred from the selector
  * (`_.claude` ⇒ `ClaudeCode`) and never written by callers; the runtime pins
  * it into `FlowContext.LeadB` so `agent` is concretely typed and sessions
  * thread.
  *
  * Overrides default to `None` so the runtime can build the default lazily —
  * `TerminalInteraction`, in particular, takes the resolved `workDir` which
  * can't be threaded through a Scala 3 default-arg expression.
  */
def flow[B <: BackendTag](
    args: OrcaArgs,
    agent: FlowContext => Agent[B],
    workDir: os.Path = os.pwd,
    interaction: Option[Interaction] = None,
    extraListeners: List[OrcaListener] = Nil,
    branchNaming: Option[BranchNamingStrategy] = None,
    returnToStartBranch: Boolean = false,
    progressStore: Option[ProgressStore] = None,
    claude: Option[ClaudeAgent] = None,
    codex: Option[CodexAgent] = None,
    opencode: Option[OpencodeAgent] = None,
    opencodeLauncher: OpencodeLauncher = OpencodeLauncher.default,
    pi: Option[PiAgent] = None,
    gemini: Option[GeminiAgent] = None,
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
  var failed = false
  try
    try
      runFlow(
        args,
        agent,
        workDir,
        interaction,
        extraListeners ++ List(costTracker),
        branchNaming,
        returnToStartBranch,
        progressStore,
        claude,
        codex,
        opencode,
        opencodeLauncher,
        pi,
        gemini,
        git,
        gh,
        fs,
        prompts
      )(body)
    catch
      // The failure was already surfaced inside the scope (the flow body runs
      // as a top-level stage). Only the exit code remains to decide — after
      // the finally below has printed the summary and detached the trace.
      case NonFatal(_) => failed = true
  finally
    costTracker.printSummary()
    orcaLog.finish()
  if failed then System.exit(1)

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
private[orca] def runFlow[B <: BackendTag](
    args: OrcaArgs,
    agent: FlowContext => Agent[B],
    workDir: os.Path,
    interaction: Option[Interaction],
    extraListeners: List[OrcaListener],
    branchNaming: Option[BranchNamingStrategy],
    returnToStartBranch: Boolean,
    progressStore: Option[ProgressStore],
    claude: Option[ClaudeAgent],
    codex: Option[CodexAgent],
    opencode: Option[OpencodeAgent],
    opencodeLauncher: OpencodeLauncher,
    pi: Option[PiAgent],
    gemini: Option[GeminiAgent],
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
    // Set once the context exists; called in the body's `finally` (below) so
    // context-owned background forks — the opencode `serve` drains — are torn
    // down BEFORE this `supervised` scope joins them. Ox runs `releaseAfterScope`
    // after the join, so this must be a body-finally, not a finalizer.
    var closeContext: () => Unit = () => ()
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
      // Order matters (and is delicate): the leading agent is a selector
      // resolved against the context, and branch setup needs the resolved agent
      // for branch naming. So the store and the context must exist BEFORE setup
      // runs:
      //   1. Resolve the progress store (pure — no git effect, no agent).
      //   2. Build the context (pure construction — backends are created but
      //      no subprocess spawns until the first gated `run`).
      //   3. Resolve the leading agent (`agent(ctx)`) and run branch setup
      //      (stash → resume-vs-fresh → checkout → header commit) using it for
      //      branch naming.
      // Teardown is unchanged (the disjoint success/failure paths below), so
      // CORE's invariants are preserved.
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
        agentSelector = agent,
        claude = claude,
        codex = codex,
        opencode = opencode,
        opencodeLauncher = opencodeLauncher,
        pi = pi,
        gemini = gemini,
        git = Some(effectiveGit),
        gh = gh,
        fs = fs,
        prompts = prompts
      )
      closeContext = () => ctx.close()
      // The context resolved the leading agent (lazily, against itself) and
      // exposes it as `ctx.agent`. The runtime needs it (erased) for branch
      // naming and session rehydration, which run before the body.
      val setup =
        FlowLifecycle.setup(args, ctx.agent, effectiveGit, branchNaming, store)
      // Rehydrate the client→server session map: each backend's registry is
      // in-memory, so on resume it starts out empty. Replay the persisted
      // records into it (after the context + log exist, before the body) so
      // `dispatchFor` resumes the right server thread and the server-id
      // existence probes work. Rehydration is targeted per record's `backend`
      // tag — untagged (older) records go to the lead, a tagged record goes to
      // that backend's agent (even when it isn't the lead), and an unknown tag
      // is skipped rather than guessed.
      FlowLifecycle.rehydrateSessions(ctx, ctx.agent, store)
      // The whole flow body runs as a top-level stage: an otherwise
      // unhandled exception surfaces as a single Error event (the same
      // message a stage failure shows). A nested stage / `fail` marks the
      // exception `alreadyEmitted` once it has reported it, so we don't
      // re-report it here. The stack goes to the trace file only (DEBUG,
      // below the console's WARN threshold); `--verbose` also prints it to
      // stderr.
      //
      // Teardown separation: body-failure and body-success teardowns are
      // completely disjoint — structurally, not flag-guarded: the catch below
      // rethrows, so success teardown is unreachable on failure. A
      // success-teardown error (e.g. a cosmetic cleanup-commit failure) must
      // NOT trigger the failure teardown (`resetHard`), and must NOT strand
      // the user on the feature branch.
      try
        // The body reads the lead via the `agent` accessor; `ctx.LeadB` (pinned
        // to `B` at construction) keeps it concretely typed so sessions thread.
        body(using ctx)
      catch
        case NonFatal(e) =>
          val alreadyEmitted = e match
            case fe: OrcaFlowException => fe.alreadyEmitted
            case _                     => false
          if !alreadyEmitted then ctx.emit(OrcaEvent.Error(throwableMessage(e)))
          flowLog.debug("flow aborted", e)
          if debug then e.printStackTrace(System.err)
          FlowLifecycle.teardownFailure(effectiveGit)
          throw e
      FlowLifecycle.teardownSuccess(effectiveGit, setup, returnToStartBranch)
    finally
      // Both run before the `supervised` scope joins its forks. closeContext
      // first: it destroys the opencode `serve` process so its drain forks'
      // reads EOF and the join can't hang.
      try closeContext()
      finally effectiveInteraction.close()

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
