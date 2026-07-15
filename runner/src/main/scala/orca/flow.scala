package orca

import orca.backend.{AgentWiring, Interaction}
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
import orca.runner.{
  DefaultFlowContext,
  FlowLifecycle,
  FlowLock,
  FlowWiring,
  LoggingListener,
  OrcaBanner,
  OrcaLog,
  SurfacedFlowFailure,
  WiredAgents
}
import orca.runner.terminal.TerminalInteraction
import orca.subprocess.OsProcCliRunner
import org.slf4j.LoggerFactory
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.tools.{OsFsTool, OsGitHubTool, OsGitTool}
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
  * one of the five wired agents (`_.claude`, …) or a `copyTool`-derived sibling
  * of one (`_.claude.opus`, `.withReadOnly`, …) — anything sharing their
  * backend is safe. A selector that instead returns an agent built from a
  * SEPARATE `AgentWiring`/backend (e.g. `_ => myPrebuiltAgent`, or an agent
  * wired against a different run entirely) compiles but is event-blind — it
  * never reaches this run's dispatcher, so its cost/steps never reach the
  * terminal or cost tracker — and gets a loud resolution-time warning; the
  * runtime still closes its backend at flow end to avoid a resource leak, but
  * nothing can retrofit it onto this run's event stream after the fact.
  *
  * The leading agent is named by a required `agent` selector resolved against
  * the run's wired [[AgentSet]]: the only way to name an agent is an accessor
  * on that set, which isn't in scope at the `flow(...)` argument position, so
  * the selector defers resolution until the agents are wired. The runtime
  * resolves it BEFORE the `FlowContext` is constructed (the context receives
  * the resolved lead as a constructor val); `FlowContext` extends `AgentSet`,
  * so the same accessors keep working inside the body. `flow(OrcaArgs(args),
  * _.claude)` runs against claude; `flow(OrcaArgs(args), _.codex)` against
  * codex, etc. Inside the body, reference the resolved lead via the
  * backend-agnostic [[agent]] accessor (not a concrete `claude`/`codex`) so
  * switching the selector switches the whole flow.
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
    agent: AgentSet => Agent[B],
    workDir: os.Path = os.pwd,
    interaction: Option[Interaction] = None,
    extraListeners: List[OrcaListener] = Nil,
    branchNaming: Option[BranchNamingStrategy] = None,
    // Explicit stack settings win outright (ADR 0019): when passed, the
    // settings file is neither read nor written — the escape hatch for
    // language-specific flows that own their tooling commands.
    stackSettings: Option[StackSettings] = None,
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
        stackSettings,
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
      // Every phase that can fail — lead resolution and setup (bracketed in
      // `runFlow`), rehydration and the body (bracketed in
      // `FlowLifecycle.run`) — is wrapped so a failure is reported to the
      // user's event surface BEFORE it escapes, then rethrown as
      // `SurfacedFlowFailure`. Seeing that marker means the message already
      // reached the user — only the exit code remains to decide (after the
      // finally below prints the summary and detaches the trace).
      case _: SurfacedFlowFailure => failed = true
      // Backstop: any other NonFatal escaped a code path that was never
      // bracketed — a pre-dispatcher failure (agent factory,
      // TerminalInteraction start) has no event surface to report to, and a
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
  // Known, not fixed — an accepted residual of the exit-based CLI contract.
  if failed then System.exit(1)

/** Exit-free flow lifecycle: builds the interaction and the wired agents,
  * resolves the lead, runs setup, constructs the context, then runs the body as
  * a top-level stage with disjoint success/failure teardown. Unlike [[flow]], a
  * failure in any phase is **propagated** (after any body-failure teardown),
  * not turned into a `System.exit` — so the crash→`resetHard`→resume wiring is
  * directly testable end-to-end. Every phase that can fail — lead resolution
  * and setup (pre-context, bracketed below), then rehydration and the body
  * (bracketed inside `FlowLifecycle.run`) — reports to the event surface first,
  * so a `NonFatal` failure from one of those escapes here wrapped in
  * [[orca.runner.SurfacedFlowFailure]]`(cause)`; tests inspect its `cause`. A
  * failure from BEFORE the dispatcher and agents exist (e.g. an agent-override
  * factory, applied just after the dispatcher is built) has no event surface to
  * report to and so escapes unwrapped instead. [[flow]] wraps this to keep the
  * observable CLI behaviour (cost summary, OrcaLog, `System.exit(1)`).
  *
  * `extraListeners` is the full listener set this run should observe beyond the
  * interaction's own (the CLI wrapper adds its [[CostTracker]] here); a
  * [[LoggingListener]] is always appended.
  */
private[orca] def runFlow[B <: BackendTag](
    args: OrcaArgs,
    agent: AgentSet => Agent[B],
    workDir: os.Path,
    interaction: Option[Interaction],
    extraListeners: List[OrcaListener],
    branchNaming: Option[BranchNamingStrategy],
    stackSettings: Option[StackSettings] = None,
    returnToStartBranch: Boolean,
    progressStore: Option[ProgressStore],
    wiring: FlowWiring = FlowWiring()
)(body: FlowControl ?=> Unit): Unit =
  val debug = OrcaDebug.enabled || args.verbose.value
  // Acquire both reentrancy/concurrency guards before anything else
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
          val store =
            progressStore.getOrElse(
              ProgressStore.default(workDir, args.userPrompt)
            )
          // One wiring bundle handed to every agent factory — overrides and
          // defaults build against the SAME event sink (dispatcher),
          // interaction, workDir and prompts, so a user agent is wired into the
          // run exactly like the default ones. Agent construction is pure —
          // backends are created but no subprocess spawns until the first gated
          // `run` — and runs BEFORE the reporting bracket below, so a factory
          // failure escapes unwrapped (there are no agents to close yet).
          val agentWiring = AgentWiring(
            events = dispatcher,
            interaction = effectiveInteraction,
            workDir = workDir,
            prompts = wiring.prompts
          )
          val agents = WiredAgents.build(wiring, agentWiring)
          val gitTool = wiring.git.getOrElse(new OsGitTool(workDir, dispatcher))
          val ghTool = wiring.gh.getOrElse(
            new OsGitHubTool(OsProcCliRunner, workDir, events = dispatcher)
          )
          val fsTool = wiring.fs.getOrElse(new OsFsTool(workDir))
          val log = LoggerFactory.getLogger("orca.flow")
          // Ownership-transfer guard: between here and the context taking
          // ownership at construction, the wired agents (and a resolved lead)
          // have no owner whose close() runs on failure. If an exception
          // escapes before the transfer, close them best-effort in the same
          // order `ctx.close()` uses — the five wired agents, then the lead.
          // git/gh/fs hold no closeable resources, so the agents are all the
          // guard needs to cover.
          var lead: Option[Agent[B]] = None
          var transferred = false
          try
            // Pre-context equivalent of `FlowLifecycle.run`'s `surfaced`
            // bracket: report the failure to the event surface, log, print the
            // stack under `--verbose`/debug, and rethrow as
            // `SurfacedFlowFailure` so `flow()` exits without re-printing. No
            // `reportOnce` here — pre-context there is no second reporter that
            // could double-report the same throwable.
            def surfaced[T](op: => T): T =
              try op
              catch
                case NonFatal(e) =>
                  dispatcher.onEvent(
                    OrcaEvent.Error(TextUtil.throwableMessage(e))
                  )
                  log.debug("flow aborted", e)
                  if debug then e.printStackTrace(System.err)
                  throw SurfacedFlowFailure(e)
            val resolvedLead = surfaced:
              val l = agent(agents)
              // A selector that escapes the five wired agents (e.g. `_ =>
              // myPrebuiltAgent`, built from a separate `AgentWiring`/backend)
              // is event-blind — it never reaches this run's dispatcher — so
              // this is a loud warning, not a silent fact; the close fan-outs
              // still close it to avoid a resource leak, but nothing can make
              // it observe this run's events after the fact.
              if !agents.isWiredBackend(l) then
                dispatcher.onEvent(
                  OrcaEvent.Step(
                    "warning: lead agent was not built from this flow's context — " +
                      "events may not reach the terminal/cost tracker"
                  )
                )
              l
            lead = Some(resolvedLead)
            // Setup (branch + log binding, stack-settings resolution) runs
            // BEFORE the context is constructed, so its outcome is a
            // constructor input rather than late-bound state.
            val flowSetup = surfaced(
              FlowLifecycle.setup(
                args,
                resolvedLead,
                gitTool,
                workDir,
                branchNaming,
                stackSettings,
                store,
                dispatcher.onEvent
              )
            )
            val ctx = new DefaultFlowContext[B](
              userPrompt = args.userPrompt,
              workDir = workDir,
              dispatcher = dispatcher,
              agent = resolvedLead,
              wired = agents,
              git = gitTool,
              gh = ghTool,
              fs = fsTool,
              progressStore = store,
              stackSettings = flowSetup.stackSettings
            )
            transferred = true
            // From here on, `ctx.close()` runs in this `finally`, BEFORE the
            // `supervised` scope joins its forks: it destroys the opencode
            // `serve` process so its drain forks' reads EOF and the join can't
            // hang (Ox runs `releaseAfterScope` only after the join).
            try
              FlowLifecycle.run(ctx, flowSetup, returnToStartBranch, debug)(
                body
              )
            finally ctx.close()
          finally
            if !transferred then WiredAgents.closeBestEffort(agents.all ++ lead)
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
