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
import orca.sessions.SessionStore
import orca.review.ReviewerCatalog
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
  WiredAgents,
  WorktreeRun
}
import orca.runner.manifest.{AttemptManifestWriter, AttemptOutcome}
import orca.runner.terminal.TerminalInteraction
import orca.subprocess.OsProcCliRunner
import org.slf4j.LoggerFactory
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.tools.{OsFsTool, OsGitHubTool, OsGitTool}
import orca.util.{OrcaDebug, TextUtil}
import ox.{Ox, resourceScope, supervised}

import java.time.Instant
import scala.util.control.NonFatal

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
  * '''`--worktree`.''' `workDir` is where the run starts looking, not always
  * where it happens: with `--worktree` the run moves into
  * `.orca/worktrees/<task hash>` of this repository, created on first use and
  * reused after, and everything below it — git, the progress log, the session
  * manifest — uses that directory instead. A refusal (no repository, no
  * commits, something orca did not create already at the path) ends the run
  * before any of that starts. `--worktree` combines with neither
  * `--skip-branch` nor `--keep-changes`, and `RunTarget` — what `args` carries
  * those three flags as — has no case for either pair, so the refusal happens
  * once, converting argv (`OrcaArgs.parse`).
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
  val flowLog = LoggerFactory.getLogger("orca.flow")
  // A daemon thread or unsupervised fork that throws would otherwise disappear
  // with no diagnostic; this leaves a trail on the console and in the trace.
  installUncaughtExceptionHandler()
  // Tally token usage and print the summary on exit (success or failure).
  val costTracker = new CostTracker(pricing.lastUpdated)
  // Read once and threaded explicitly from here down (AttemptManifestWriter, and
  // the progress header via `runFlow`/`FlowLifecycle.setup`) rather than
  // re-read with `sys.env` at each site.
  val flowName = sys.env.get("ORCA_FLOW_NAME")
  val runKey = RunKey.of(args.userPrompt)

  // Where the run happens. This settles before the directory's first consumer,
  // the attempt's trace below; everything downstream is handed `workDir`
  // explicitly, so it is the single value to change.
  def resolveRunDir(): Either[String, os.Path] = args.target match
    case RunTarget.NewBranch(_) | RunTarget.CurrentBranch(_) => Right(workDir)
    case RunTarget.Worktree                                  =>
      // Resolution can throw as well as refuse — a symlinked or unwritable
      // `.orca`, a git that won't start. One `Left` shape for every outcome
      // keeps the reporting below the only way out.
      try WorktreeRun.resolve(workDir, runKey)
      catch case NonFatal(e) => Left(TextUtil.throwableMessage(e))

  // The attempt's trace file, capturing every stage, prompt and
  // tool/subprocess call at DEBUG. It lives under `dir`, so resolving `dir` is
  // not traced.
  def startTrace(dir: os.Path, attemptId: AttemptId): OrcaLog =
    val _ = OrcaDir.ensureAttempts(dir)
    val orcaLog = OrcaLog.start(dir, attemptId)
    OrcaBanner.print(System.err, orcaLog.file)
    flowLog.info("user prompt: {}", args.userPrompt)
    val where = args.target match
      case RunTarget.Worktree => s"$dir (worktree)"
      case RunTarget.NewBranch(_) | RunTarget.CurrentBranch(_) => dir.toString
    flowLog.info("orca {} starting (workDir={})", OrcaBanner.version, where)
    orcaLog

  // The run proper. Everything under here uses `dir`, never `workDir`.
  def runIn(dir: os.Path): AttemptOutcome =
    val clock = () => Instant.now()
    val attemptId = AttemptId(clock(), ProcessHandle.current().pid())
    val orcaLog = startTrace(dir, attemptId)
    try runAttempt(dir, attemptId, clock)
    finally orcaLog.finish()

  def runAttempt(
      dir: os.Path,
      attemptId: AttemptId,
      clock: () => Instant
  ): AttemptOutcome =
    supervised:
      // Per-attempt manifest (ADR 0021 §8), always attached like
      // LoggingListener; see AttemptManifestWriter's scaladoc for `flowName`'s
      // ORCA_FLOW_NAME sourcing. Its actor fork lives in this scope, spanning
      // construction through `finish`; the `System.exit` at the end of `flow()`
      // stays OUTSIDE it, and a nested `flow()` gets its own scope and writer.
      val manifestWriter = AttemptManifestWriter.start(
        dir,
        OrcaBanner.version,
        flowName,
        attemptId,
        clock
      )
      var outcome: Option[AttemptOutcome] = None
      // `try/finally` so the cost summary always lands — even when a fatal
      // throwable (OOM, StackOverflow) escapes the NonFatal catch below.
      try
        val result =
          try
            runFlow(
              args = args,
              workDir = dir,
              interaction = interaction,
              extraListeners =
                extraListeners ++ List(costTracker, manifestWriter),
              branchNaming = branchNaming,
              stackSettings = stackSettings,
              planningAgent = planningAgent,
              codingAgent = codingAgent,
              reviewAgent = reviewAgent,
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
            AttemptOutcome.Succeeded
          catch
            // A `SurfacedFlowFailure` marks a failure already reported to the
            // user's event surface by the phase that raised it; only the exit
            // code remains.
            case _: SurfacedFlowFailure => AttemptOutcome.Failed
            // Backstop for any other NonFatal — a pre-dispatcher failure (agent
            // factory, TerminalInteraction start) has no event surface, so print
            // it to stderr rather than exit 1 in silence.
            case NonFatal(e) =>
              System.err.println(s"[orca] ${TextUtil.throwableMessage(e)}")
              AttemptOutcome.Failed
        outcome = Some(result)
        result
      finally
        // `None` only when a fatal throwable escapes the `NonFatal` catch.
        manifestWriter.finish(outcome.getOrElse(AttemptOutcome.Failed))
        costTracker.printSummary()

  val outcome = resolveRunDir() match
    // A refusal has no dispatcher, manifest or trace to carry it, so it reaches
    // the user the way the NonFatal backstop above does.
    case Left(message) =>
      System.err.println(s"[orca] $message")
      AttemptOutcome.Failed
    case Right(dir) => runIn(dir)
  // Known residual: in a NESTED `flow()` call this `System.exit` tears down the
  // JVM before the OUTER flow's `finally` (branch restore, lock release) runs,
  // leaving the outer branch checked out and `.orca/cache/flow.lock` behind (the next
  // attempt self-heals by stealing the dead-PID lock). Accepted cost of the
  // exit-based CLI contract.
  if outcome == AttemptOutcome.Failed then System.exit(1)

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
  * appended. `configHome` is overridden only by tests, which must never read
  * the developer's real `~/.config`.
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
    progressStore: Option[ProgressStore],
    configHome: ConfigHome = ConfigHome.default,
    // `ORCA_FLOW_NAME`, forwarded into a freshly-written progress header (see
    // `FlowLifecycle.setup`'s own scaladoc) — `flow()` passes its real
    // `sys.env` reading; `None` for every other caller (tests, a nested
    // `flow()` invocation with nothing of its own to report).
    flowName: Option[String] = None,
    wiring: FlowWiring = FlowWiring(),
    pricing: PriceList = Pricing.default
)(body: FlowControl ?=> Unit): Unit =
  val debug = OrcaDebug.enabled || args.verbose
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
          val runKey = RunKey.of(args.userPrompt)
          val store =
            progressStore.getOrElse(ProgressStore.default(workDir, runKey))
          // Not pluggable alongside `progressStore`: these records are
          // machine-local cache under `.orca/cache/`, derived from the same
          // (workDir, prompt) pair a resumed run re-derives.
          val sessions = SessionStore.default(workDir, runKey)
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
          runInContext(
            args = args,
            workDir = workDir,
            stackSettings = stackSettings,
            planningAgent = planningAgent,
            codingAgent = codingAgent,
            reviewAgent = reviewAgent,
            configHome = configHome,
            branchNaming = branchNaming,
            dispatcher = dispatcher,
            agents = agents,
            gitTool = gitTool,
            ghTool = ghTool,
            fsTool = fsTool,
            store = store,
            sessions = sessions,
            flowName = flowName,
            debug = debug
          )(body)
        finally effectiveInteraction.close()
    finally FlowLock.releaseWorkdir(lockPath)
  finally FlowLock.releaseProcess()

/** The settings→roles→setup→context→body sequence of `runFlow`: read both
  * settings files, resolve the three role agents (`RoleAgents.resolveAll`, ADR
  * 0020 §10), run pre-context setup (branch + log binding, stack discovery,
  * `FlowLifecycle.setup`), construct the concretely-typed
  * [[DefaultFlowContext]], then run `body` in it.
  *
  * Owns closing the agents: the wired ones and any FOREIGN role (an override
  * from a separate backend) are closed when this returns, on success or
  * failure. git/gh/fs hold no closeable resources.
  */
private def runInContext(
    args: OrcaArgs,
    workDir: os.Path,
    stackSettings: Option[StackSettings],
    planningAgent: Option[AgentSet => Agent[?]],
    codingAgent: Option[AgentSet => Agent[?]],
    reviewAgent: Option[AgentSet => Agent[?]],
    configHome: ConfigHome,
    branchNaming: Option[BranchNamingStrategy],
    dispatcher: OrcaListener,
    agents: WiredAgents,
    gitTool: GitTool,
    ghTool: GitHubTool,
    fsTool: FsTool,
    store: ProgressStore,
    sessions: SessionStore,
    flowName: Option[String],
    debug: Boolean
)(body: FlowControl ?=> Unit): Unit =
  val log = LoggerFactory.getLogger("orca.flow")
  // A resource scope rather than `supervised`'s own `releaseAfterScope`, which
  // runs only after the scope joins its forks: closing destroys the opencode
  // `serve` process, which is what makes its drain forks' reads EOF, so it
  // must run first or the join hangs. This method takes no `Ox`, as
  // `resourceScope` can't start where one is visible.
  resourceScope:
    WiredAgents.closeAfterScope(agents.all)
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
        FlowLifecycle.readSettings(workDir, configHome.settings, stackSettings)
      val resolution = RoleAgents.resolveAll(
        read.projectAgents,
        read.globalAgents,
        RoleOverrides(planningAgent, codingAgent, reviewAgent),
        agents
      )
      resolution.foreignWarnings.foreach: warning =>
        dispatcher.onEvent(OrcaEvent.Step(warning))
      dispatcher.onEvent(OrcaEvent.Step(resolution.announcement))
      (resolution.roles, read)
    // The run's reviewer definitions, resolved once and frozen. Inside
    // `surfaced` and before setup for the same reason as the settings above: a
    // malformed reviewer file aborts before any tree mutation.
    val reviewerCatalog = surfaced:
      val projectReviewersPath = OrcaDir.reviewersPath(workDir)
      // `ReviewerCatalog.discover` reads through `os.isDir`, which follows
      // links, so the tier directory is guarded here.
      OrcaDir.assertNoOrcaSymlinks(workDir, projectReviewersPath)
      val catalog =
        ReviewerCatalog.discover(projectReviewersPath, configHome.reviewers)
      catalog.describe.foreach(d => dispatcher.onEvent(OrcaEvent.Step(d)))
      catalog
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
        sessions,
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
          sessionStore = sessions,
          stackSettings = flowSetup.stackSettings,
          reviewerCatalog = reviewerCatalog,
          startingCommit = flowSetup.startingCommit
        )
    FlowLifecycle.run(ctx, flowSetup, debug = debug)(body)

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
