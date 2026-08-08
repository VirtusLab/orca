package orca

import orca.backend.{AgentWiring, Interaction}
import orca.events.{
  CostResolvingDispatcher,
  CostTracker,
  EventDispatcher,
  OrcaEvent,
  OrcaListener,
  PriceList,
  Pricing
}
import orca.agents.{
  Agent,
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
  RoleAgents,
  RoleOverrides,
  SurfacedFlowFailure,
  WiredAgents
}
import orca.runner.manifest.{RunManifestWriter, RunOutcome}
import orca.settings.GlobalSettings
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

/** Outcome of the flow body, tracked across the try/catch/finally below. One
  * ADT rather than two booleans (which would admit "both true", impossible
  * here, but couldn't express `Running` — what's left when a fatal throwable,
  * e.g. OOM, escapes the `NonFatal` catch). The exit-code check treats only
  * `Failed` as exit 1; the manifest write maps anything but `Succeeded` to
  * `RunOutcome.Failed` — an honest "failed" report even when no catch ran.
  */
private enum FlowOutcome:
  case Running, Succeeded, Failed

/** Entry point for flow scripts. Takes the parsed CLI args (required) plus any
  * number of overrides, then runs the body, providing the `FlowContext` as a
  * given.
  *
  * ```
  * flow(OrcaArgs(args)):
  *   val plan = planningAgent.resultAs[Plan].autonomous.run(userPrompt)
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
  * agents — see [[orca.runner.FlowWiring]] for the shared shape. Start from a
  * per-backend factory and tune it, `claude = Some(w =>
  * ClaudeAgents.default(w).opus)`, or wrap a prebuilt agent `claude = Some(_ =>
  * myAgent)`. Select a non-default opencode launcher through the factory
  * itself: `opencode = Some(w => OpencodeAgents.default(w,
  * OpencodeLauncher.ollama("qwen3-coder")))`.
  *
  * '''Role agents (ADR 0020).''' A run has three role agents —
  * [[orca.planningAgent]], [[orca.codingAgent]], [[orca.reviewAgent]] —
  * resolved from settings, not a script-level selector. Precedence, per role:
  * the programmatic override below > the project file
  * `{workDir}/.orca/settings.properties` > the user-global file
  * `$XDG_CONFIG_HOME/orca/settings.properties` > the built-in default (claude,
  * default model). Each file carries `planningAgent`/`codingAgent`/
  * `reviewAgent = harness[:model]` lines; a malformed or unreadable value in
  * either file aborts the run before any tree mutation. Setup emits one `Step`
  * naming each resolved role and its source, the handle for "why did codex run
  * here?".
  *
  * The three overrides are the programmatic top of that precedence — selector-
  * shaped (`Some(_.claude.opus)`) so a `copyTool`-derived sibling stays
  * expressible, and the seam tests use in place of a global file. Each must
  * resolve to one of the wired agents or a sibling — anything sharing their
  * backend. An override returning an agent built from a SEPARATE
  * `AgentWiring`/backend (e.g. `_ => myPrebuiltAgent`) compiles but is
  * event-blind: it never reaches this run's dispatcher, so its cost/steps never
  * surface, and it gets a loud resolution-time warning. Its backend is still
  * closed at flow end to avoid a leak.
  *
  * `stackSettings` wins outright for the stack commands (ADR 0019): when
  * passed, the project file's stack keys are ignored and discovery is skipped,
  * but its agent keys are still honoured (a malformed file still aborts).
  *
  * Overrides default to `None` so the runtime can build the default lazily —
  * `TerminalInteraction` in particular takes the resolved `workDir`, which
  * can't be threaded through a default-arg expression.
  */
def flow(
    args: OrcaArgs,
    workDir: os.Path = os.pwd,
    interaction: Option[Interaction] = None,
    extraListeners: List[OrcaListener] = Nil,
    branchNaming: Option[BranchNamingStrategy] = None,
    stackSettings: Option[StackSettings] = None,
    planningAgent: Option[AgentSet => Agent[?]] = None,
    codingAgent: Option[AgentSet => Agent[?]] = None,
    reviewAgent: Option[AgentSet => Agent[?]] = None,
    returnToStartBranch: Boolean = false,
    progressStore: Option[ProgressStore] = None,
    // Agent factories share the `AgentWiring => Ox ?=> Agent` shape — see
    // FlowWiring's scaladoc.
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
  // Per-run trace file capturing every stage, prompt and tool/subprocess call
  // at DEBUG. Started before anything logs so the whole run is caught.
  val orcaLog = OrcaLog.start()
  OrcaBanner.print(System.err, orcaLog.file)
  val flowLog = LoggerFactory.getLogger("orca.flow")
  flowLog.info("orca {} starting (workDir={})", OrcaBanner.version, workDir)
  flowLog.info("user prompt: {}", args.userPrompt)
  // A daemon thread or unsupervised fork that throws would otherwise disappear
  // with no diagnostic; this leaves a trail on the console and in the trace.
  installUncaughtExceptionHandler()
  // Tally token usage and print the summary on exit (success or failure).
  val costTracker = new CostTracker(pricing.lastUpdated)
  // `try/finally` so the cost summary always lands — even when a fatal
  // throwable (OOM, StackOverflow) escapes the NonFatal catch below. See
  // `FlowOutcome`'s scaladoc for why this is one ADT, not two booleans.
  var outcome = FlowOutcome.Running
  // The manifest writer's actor fork lives in this scope, spanning its
  // construction through `finish`; `System.exit` below stays OUTSIDE it. A
  // nested `flow()` call gets its own scope and writer.
  // Read once and threaded explicitly from here down (RunManifestWriter,
  // and the progress header via `runFlow`/`FlowLifecycle.setup`) rather than
  // re-read with `sys.env` at each site.
  val flowName = sys.env.get("ORCA_FLOW_NAME")
  supervised:
    // Per-run session manifest (ADR 0021 §8), always attached like
    // LoggingListener; see RunManifestWriter's scaladoc for `flowName`'s
    // ORCA_FLOW_NAME sourcing.
    val manifestWriter = RunManifestWriter.start(
      workDir,
      OrcaBanner.version,
      flowName,
      () => java.time.Instant.now()
    )
    try
      try
        runFlow(
          args = args,
          workDir = workDir,
          interaction = interaction,
          extraListeners = extraListeners ++ List(costTracker, manifestWriter),
          branchNaming = branchNaming,
          stackSettings = stackSettings,
          planningAgent = planningAgent,
          codingAgent = codingAgent,
          reviewAgent = reviewAgent,
          returnToStartBranch = returnToStartBranch,
          progressStore = progressStore,
          flowName = flowName,
          pricing = pricing,
          wiring = FlowWiring(
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
        outcome = FlowOutcome.Succeeded
      catch
        // A `SurfacedFlowFailure` marks a failure already reported to the user's
        // event surface by the phase that raised it; only the exit code remains.
        case _: SurfacedFlowFailure => outcome = FlowOutcome.Failed
        // Backstop for any other NonFatal — a pre-dispatcher failure (agent
        // factory, TerminalInteraction start) has no event surface, so print it
        // to stderr rather than exit 1 in silence.
        case NonFatal(e) =>
          outcome = FlowOutcome.Failed
          System.err.println(s"[orca] ${TextUtil.throwableMessage(e)}")
    finally
      manifestWriter.finish(
        if outcome == FlowOutcome.Succeeded then RunOutcome.Succeeded
        else RunOutcome.Failed
      )
      costTracker.printSummary()
      orcaLog.finish()
  // Known residual: in a NESTED `flow()` call this `System.exit` tears down the
  // JVM before the OUTER flow's `finally` (branch restore, lock release) runs,
  // leaving the outer branch checked out and `.orca/flow.lock` behind (the next
  // run self-heals by stealing the dead-PID lock). Accepted cost of the
  // exit-based CLI contract.
  if outcome == FlowOutcome.Failed then System.exit(1)

/** Exit-free flow lifecycle: builds the interaction and wired agents, resolves
  * the three role agents from settings, runs setup, constructs the context,
  * then runs the body as a top-level stage with disjoint success/failure
  * teardown. Unlike [[flow]], a failure in any phase is **propagated** (after
  * body-failure teardown), not turned into a `System.exit`, so the
  * crash→`discardUncommitted`→resume wiring is directly testable. A phase that
  * reports to the event surface first escapes wrapped in
  * [[orca.runner.SurfacedFlowFailure]]`(cause)`; a failure from BEFORE the
  * dispatcher and agents exist (e.g. an agent-override factory) has no event
  * surface and escapes unwrapped.
  *
  * `extraListeners` is the listener set beyond the interaction's own (the CLI
  * wrapper adds its [[CostTracker]] here); a [[LoggingListener]] is always
  * appended. `globalSettingsPath` is overridden only by tests, which must never
  * read the developer's real `~/.config`.
  */
private[orca] def runFlow(
    args: OrcaArgs,
    workDir: os.Path,
    interaction: Option[Interaction],
    extraListeners: List[OrcaListener],
    branchNaming: Option[BranchNamingStrategy],
    stackSettings: Option[StackSettings] = None,
    planningAgent: Option[AgentSet => Agent[?]] = None,
    codingAgent: Option[AgentSet => Agent[?]] = None,
    reviewAgent: Option[AgentSet => Agent[?]] = None,
    returnToStartBranch: Boolean,
    progressStore: Option[ProgressStore],
    globalSettingsPath: os.Path = GlobalSettings.default,
    // `ORCA_FLOW_NAME`, forwarded into a freshly-written progress header (see
    // `FlowLifecycle.setup`'s own scaladoc) — `flow()` passes its real
    // `sys.env` reading; `None` for every other caller (tests, a nested
    // `flow()` invocation with nothing of its own to report).
    flowName: Option[String] = None,
    wiring: FlowWiring = FlowWiring(),
    pricing: PriceList = Pricing.default
)(body: FlowControl ?=> Unit): Unit =
  val debug = OrcaDebug.enabled || args.verbose.value
  // Acquire both guards before `supervised:` (neither needs an `Ox` scope) so a
  // violation is caught before any git mutation. See [[FlowLock]] for the
  // two-layer rationale and release-ordering symmetry.
  FlowLock.acquireProcess()
  try
    val lockPath = FlowLock.acquireWorkdir(workDir)
    try
      // Default TerminalInteraction is built inside `supervised:` because its
      // worker is a `forkUser` bound to that scope; close() in the body's
      // `finally` lets it drain before the scope joins it.
      supervised:
        val effectiveInteraction = interaction.getOrElse(
          TerminalInteraction.start(workDir = Some(workDir))
        )
        try
          // Cost is resolved on the way in, so the terminal summary, the
          // on-disk cost log and any listener a caller added all read one
          // figure — none of them holds a price table of its own.
          val dispatcher: OrcaListener = new CostResolvingDispatcher(
            pricing,
            new EventDispatcher(
              effectiveInteraction.listeners ++ List(
                new LoggingListener
              ) ++ extraListeners
            )
          )
          val store =
            progressStore.getOrElse(
              ProgressStore.default(workDir, args.userPrompt)
            )
          // One wiring bundle handed to every agent factory, so overrides and
          // defaults build against the SAME dispatcher, interaction, workDir and
          // prompts. Agent construction is pure (no subprocess spawns until the
          // first gated `run`) and runs BEFORE the reporting bracket below, so a
          // factory failure escapes unwrapped (no agents to close yet).
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
          val (ctx, flowSetup) = buildContext(
            args = args,
            workDir = workDir,
            stackSettings = stackSettings,
            planningAgent = planningAgent,
            codingAgent = codingAgent,
            reviewAgent = reviewAgent,
            globalSettingsPath = globalSettingsPath,
            branchNaming = branchNaming,
            dispatcher = dispatcher,
            agents = agents,
            gitTool = gitTool,
            ghTool = ghTool,
            fsTool = fsTool,
            store = store,
            flowName = flowName,
            debug = debug
          )
          // `ctx.close()` runs here, BEFORE the `supervised` scope joins its
          // forks: it destroys the opencode `serve` process so its drain
          // forks' reads EOF and the join can't hang (Ox runs
          // `releaseAfterScope` only after the join).
          try
            FlowLifecycle.run(
              ctx,
              flowSetup,
              returnToStartBranch = returnToStartBranch,
              debug = debug
            )(body)
          finally ctx.close()
        finally effectiveInteraction.close()
    finally FlowLock.releaseWorkdir(lockPath)
  finally FlowLock.releaseProcess()

/** The settings→roles→setup→context sequence `runFlow` needs before the body
  * can run: read both settings files, resolve the three role agents
  * (`RoleAgents.resolveAll`, ADR 0020 §10), run pre-context setup (branch + log
  * binding, stack discovery, `FlowLifecycle.setup`), then construct the
  * concretely-typed [[DefaultFlowContext]].
  *
  * Owns its own ownership-transfer guard: until the context takes ownership at
  * construction, the wired and role agents have no owner whose close() runs on
  * failure. If an exception escapes before that point, this closes them
  * best-effort — the wired agents, then any FOREIGN role (an override from a
  * separate backend). A settings-resolved or `copyTool`-sibling role shares a
  * wired backend already covered by `agents.all`, so it's filtered out to avoid
  * a double close. git/gh/fs hold no closeable resources. Once this returns,
  * ownership has transferred to the returned context — closing it is the
  * caller's job (`runFlow`'s own `try/finally` around the body run).
  */
private def buildContext(
    args: OrcaArgs,
    workDir: os.Path,
    stackSettings: Option[StackSettings],
    planningAgent: Option[AgentSet => Agent[?]],
    codingAgent: Option[AgentSet => Agent[?]],
    reviewAgent: Option[AgentSet => Agent[?]],
    globalSettingsPath: os.Path,
    branchNaming: Option[BranchNamingStrategy],
    dispatcher: OrcaListener,
    agents: WiredAgents,
    gitTool: GitTool,
    ghTool: GitHubTool,
    fsTool: FsTool,
    store: ProgressStore,
    flowName: Option[String],
    debug: Boolean
): (DefaultFlowContext[?, ?, ?], FlowLifecycle.FlowSetup) =
  val log = LoggerFactory.getLogger("orca.flow")
  var roles: List[Agent[?]] = Nil
  var transferred = false
  try
    // Pre-context equivalent of `FlowLifecycle.run`'s `surfaced` bracket:
    // report the failure to the event surface, log, print the stack under
    // debug, and rethrow as `SurfacedFlowFailure` so `flow()` exits without
    // re-printing.
    def surfaced[T](op: => T): T =
      try op
      catch
        case NonFatal(e) =>
          dispatcher.onEvent(OrcaEvent.Error(TextUtil.throwableMessage(e)))
          log.debug("flow aborted", e)
          if debug then e.printStackTrace(System.err)
          throw SurfacedFlowFailure(e)
    // Read both settings files, then resolve the three roles and derived
    // announcement/warnings in one place (`RoleAgents.resolveAll`, ADR 0020
    // §10). Inside `surfaced` so a malformed file, bad model pin or throwing
    // override reaches the event surface before aborting, and BEFORE any tree
    // mutation (setup runs after).
    val (resolvedRoles, settingsRead) = surfaced:
      val read =
        FlowLifecycle.readSettings(workDir, globalSettingsPath, stackSettings)
      // Cover each resolved role in the close guard AS it resolves, appended
      // incrementally (not from the returned `RoleResolution`) so an earlier
      // foreign role is still closed when a LATER override throws and
      // `resolveAll` never returns.
      val resolution = RoleAgents.resolveAll(
        read.projectAgents,
        read.globalAgents,
        RoleOverrides(planningAgent, codingAgent, reviewAgent),
        agents,
        onRoleResolved = agent => roles = roles :+ agent
      )
      resolution.foreignWarnings.foreach: warning =>
        dispatcher.onEvent(OrcaEvent.Step(warning))
      dispatcher.onEvent(OrcaEvent.Step(resolution.announcement))
      (resolution.roles, read)
    // Setup (branch + log binding, stack discovery) runs BEFORE the context so
    // its outcome is a constructor input; it drives the CODING role.
    val flowSetup = surfaced(
      FlowLifecycle.setup(
        args,
        resolvedRoles.coding,
        gitTool,
        workDir,
        branchNaming,
        settingsRead.stack,
        stackOverridden = stackSettings.isDefined,
        store,
        flowName = flowName,
        emit = dispatcher.onEvent
      )
    )
    // Open the three runtime `Agent[?]` roles into their own backend tags so
    // `DefaultFlowContext` is concretely typed and each role's sessions
    // thread.
    val ctx = (
      resolvedRoles.planning,
      resolvedRoles.coding,
      resolvedRoles.review
    ) match
      case (p: Agent[pb], c: Agent[cb], r: Agent[rb]) =>
        new DefaultFlowContext[pb, cb, rb](
          userPrompt = args.userPrompt,
          workDir = workDir,
          dispatcher = dispatcher,
          planningAgent = p,
          codingAgent = c,
          reviewAgent = r,
          wired = agents,
          git = gitTool,
          gh = ghTool,
          fs = fsTool,
          progressStore = store,
          stackSettings = flowSetup.stackSettings
        )
    transferred = true
    (ctx, flowSetup)
  finally
    if !transferred then
      WiredAgents.closeBestEffort(
        agents.all ++ roles.filterNot(agents.isWiredBackend)
      )

private def installUncaughtExceptionHandler(): Unit =
  // Idempotent across nested or repeated `flow(...)` calls: install only if no
  // handler is already in place. The `orca` logger routes to the trace file
  // only, so the message goes to stderr and the stack into the trace.
  if Thread.getDefaultUncaughtExceptionHandler == null then
    val log = LoggerFactory.getLogger("orca")
    Thread.setDefaultUncaughtExceptionHandler: (thread, throwable) =>
      System.err.println(
        s"[orca] uncaught exception on thread '${thread.getName}': " +
          throwable.getMessage
      )
      log.debug("uncaught exception stack trace", throwable)
