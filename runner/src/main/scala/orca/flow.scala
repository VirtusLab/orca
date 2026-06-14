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
import orca.llm.{ClaudeTool, DefaultPrompts, OpencodeTool, PiTool, Prompts}
import orca.tools.opencode.OpencodeLauncher
import orca.runner.{DefaultFlowContext, LoggingListener, OrcaBanner, OrcaLog}
import orca.runner.terminal.TerminalInteraction
import org.slf4j.LoggerFactory
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
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
    workDir: os.Path = os.pwd,
    interaction: Option[Interaction] = None,
    extraListeners: List[OrcaListener] = Nil,
    claude: Option[ClaudeTool] = None,
    opencode: Option[OpencodeTool] = None,
    opencodeLauncher: OpencodeLauncher = OpencodeLauncher.default,
    pi: Option[PiTool] = None,
    git: Option[GitTool] = None,
    gh: Option[GitHubTool] = None,
    fs: Option[FsTool] = None,
    prompts: Prompts = DefaultPrompts,
    pricing: PriceList = Pricing.default
)(body: FlowContext ?=> Unit): Unit =
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
          val ctx = DefaultFlowContext.withDefaults(
            userPrompt = args.userPrompt,
            dispatcher = dispatcher,
            workDir = workDir,
            interaction = effectiveInteraction,
            claude = claude,
            opencode = opencode,
            opencodeLauncher = opencodeLauncher,
            pi = pi,
            git = git,
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
          try body(using ctx)
          catch
            case NonFatal(e) =>
              val alreadyEmitted = e match
                case fe: OrcaFlowException => fe.alreadyEmitted
                case _                     => false
              if !alreadyEmitted then
                ctx.emit(OrcaEvent.Error(flowErrorMessage(e)))
              flowLog.debug("flow aborted", e)
              if debug then e.printStackTrace(System.err)
              throw e
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

private def installUncaughtExceptionHandler(): Unit =
  // Idempotent across nested or repeated `flow(...)` calls — we only install
  // our handler if no app-specific one is already in place. The message goes
  // to the console (ERROR); the stack to the trace file only (DEBUG, below the
  // console's WARN threshold) so a silent exit still leaves a full trail.
  if Thread.getDefaultUncaughtExceptionHandler == null then
    val log = LoggerFactory.getLogger("orca")
    Thread.setDefaultUncaughtExceptionHandler: (thread, throwable) =>
      log.error(
        "uncaught exception on thread '{}': {}",
        thread.getName,
        throwable.getMessage
      )
      log.debug("uncaught exception stack trace", throwable)

/** The console message for a flow-aborting throwable: its message — kept whole,
  * unlike a stage's first-line `shortMessage`, so a multi-line message such as
  * opencode's start-failure stderr stays useful — or the class name when there
  * is none. The full stack goes to the trace file separately.
  */
private def flowErrorMessage(e: Throwable): String =
  Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getName)
