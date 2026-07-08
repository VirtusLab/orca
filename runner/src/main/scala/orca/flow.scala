package orca

import orca.backend.{AgentWiring, Interaction}
import orca.events.{
  CostTracker,
  EventDispatcher,
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
import orca.runner.{
  DefaultFlowContext,
  FlowLifecycle,
  FlowLock,
  FlowWiring,
  LoggingListener,
  OrcaBanner,
  OrcaLog,
  SurfacedFlowFailure
}
import orca.runner.terminal.TerminalInteraction
import org.slf4j.LoggerFactory
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.util.{OrcaDebug, TextUtil}
import ox.{Ox, supervised}

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
  * Agent overrides are `AgentWiring => Ox ?=> Agent` factories, not prebuilt
  * agents — see [[orca.runner.FlowWiring]] for why and for the shared `Ox ?=>`
  * shape. Start from a per-backend factory and tune it, `claude = Some(w =>
  * ClaudeAgents.default(w).opus)`, or wrap a prebuilt agent `claude = Some(_ =>
  * myAgent)`. There's no separate `opencodeLauncher` parameter — select a
  * non-default launcher through the factory itself: `opencode = Some(w =>
  * OpencodeAgents.default(w, OpencodeLauncher.ollama("qwen3-coder")))`.
  *
  * The `agent` selector below is constrained the same way: it must resolve to
  * one of the five wired agents (`ctx.claude`, …) or a `copyTool`-derived
  * sibling of one (`_.claude.opus`, `.withReadOnly`, …) — anything sharing
  * their backend is safe. A selector that instead returns an agent built from a
  * SEPARATE `AgentWiring`/backend (e.g. `_ => myPrebuiltAgent`, or an agent
  * wired against a different run entirely) compiles but is event-blind — it
  * never reaches this run's dispatcher, so its cost/steps never reach the
  * terminal or cost tracker — and gets a loud construction-time warning; `ctx`
  * still closes its backend at flow end to avoid a resource leak, but nothing
  * can retrofit it onto this run's event stream after the fact.
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
    // Every field shares the `AgentWiring => Ox ?=> Agent` shape — see
    // FlowWiring's scaladoc for why.
    claude: Option[AgentWiring => Ox ?=> ClaudeAgent] = None,
    codex: Option[AgentWiring => Ox ?=> CodexAgent] = None,
    opencode: Option[AgentWiring => Ox ?=> OpencodeAgent] = None,
    pi: Option[AgentWiring => Ox ?=> PiAgent] = None,
    gemini: Option[AgentWiring => Ox ?=> GeminiAgent] = None,
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
        FlowWiring(
          claude = claude,
          codex = codex,
          opencode = opencode,
          pi = pi,
          gemini = gemini,
          git = git,
          gh = gh,
          fs = fs,
          prompts = prompts
        )
      )(body)
    catch
      // Every phase inside `FlowLifecycle.run` is wrapped so a failure is
      // reported to the user's event surface BEFORE it escapes, then rethrown
      // as `SurfacedFlowFailure`. Seeing that marker means the message already
      // reached the user — only the exit code remains to decide (after the
      // finally below prints the summary and detaches the trace).
      case _: SurfacedFlowFailure => failed = true
      // Backstop: any other NonFatal escaped a code path that was never
      // bracketed — a pre-`ctx` failure (agent factory, TerminalInteraction
      // start, context construction) has no event surface to report to, and a
      // future unsurfaced path would otherwise exit 1 in silence. Print it
      // loudly to stderr so no failure is ever swallowed.
      case NonFatal(e) =>
        failed = true
        System.err.println(s"[orca] ${TextUtil.throwableMessage(e)}")
  finally
    costTracker.printSummary()
    orcaLog.finish()
  // Residual: for a NESTED `flow()` call (the outer flow's body invoking
  // `flow()` again), this `System.exit` tears down the JVM before the OUTER
  // flow's own `finally` (branch restore, workdir-lock release) ever runs —
  // the outer branch is left checked out and `.orca/flow.lock` stays behind
  // (self-heals: the next run steals the dead-PID lock with a warning).
  // Known, not fixed — see complexity-review-2.md Epic 7 note ("Epic 7
  // residual").
  if failed then System.exit(1)

/** Exit-free flow lifecycle: builds the interaction/context, runs setup, then
  * runs the body as a top-level stage with disjoint success/failure teardown.
  * Unlike [[flow]], a failure in any phase is **propagated** (after any
  * body-failure teardown), not turned into a `System.exit` — so the
  * crash→`resetHard`→resume wiring is directly testable end-to-end. Every phase
  * that can fail — setup, rehydration, and the body — runs inside
  * `FlowLifecycle.run`'s reporting bracket, so a `NonFatal` failure from one of
  * those escapes here wrapped in [[orca.runner.SurfacedFlowFailure]]`(cause)`
  * (already reported to the event surface); tests inspect its `cause`. A
  * failure from BEFORE `ctx` exists (e.g. an agent-override factory, built just
  * above) has no bracket to run inside and so escapes unwrapped instead — see
  * [[orca.runner.FlowLifecycle.run]]'s scaladoc. [[flow]] wraps this to keep
  * the observable CLI behaviour (cost summary, OrcaLog, `System.exit(1)`).
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
    wiring: FlowWiring = FlowWiring()
)(body: FlowControl ?=> Unit): Unit =
  val debug = OrcaDebug.enabled || args.verbose.value
  // Epic 7.4: acquire both reentrancy/concurrency guards before anything else
  // — including before `supervised:`, since neither needs an `Ox` scope — so
  // a violation is caught before any git mutation. See [[FlowLock]] for the
  // two-layer rationale and the release-ordering symmetry.
  FlowLock.acquireProcess()
  try
    val lockPath = FlowLock.acquireWorkdir(workDir)
    try
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
          // Construction order matters: `FlowLifecycle.run`'s setup phase resolves
          // the leading agent (for branch naming) and reads `ctx.git`, so the
          // store and the context must both exist BEFORE it runs.
          //   1. Resolve the progress store (pure — no git effect, no agent).
          //   2. Build the context (pure construction — backends are created but
          //      no subprocess spawns until the first gated `run`).
          // `FlowLifecycle.run` (below) then resolves the leading agent and drives
          // the rest of the phase protocol (setup → rehydrate → body → teardown).
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
            wiring = wiring
          )
          // Construction is pure (backends spawn nothing until the first gated
          // `run`), so a failure before this point has nothing to close — `ctx`
          // doesn't exist yet, and the outer `finally` below only closes the
          // interaction. From here on, `ctx.close()` runs in this `finally`,
          // BEFORE the `supervised` scope joins its forks: it destroys the
          // opencode `serve` process so its drain forks' reads EOF and the join
          // can't hang (Ox runs `releaseAfterScope` only after the join).
          try
            FlowLifecycle.run(
              args,
              ctx,
              branchNaming,
              returnToStartBranch,
              debug
            )(
              body
            )
          finally ctx.close()
        finally effectiveInteraction.close()
    finally FlowLock.releaseWorkdir(lockPath)
  finally FlowLock.releaseProcess()

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
