package orca

import orca.backend.Interaction
import orca.events.{
  CostTracker,
  EventDispatcher,
  OrcaListener,
  PriceList,
  Pricing
}
import orca.llm.{ClaudeTool, DefaultPrompts, Prompts}
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
    git: Option[GitTool] = None,
    gh: Option[GitHubTool] = None,
    fs: Option[FsTool] = None,
    prompts: Prompts = DefaultPrompts,
    pricing: PriceList = Pricing.default
)(body: FlowContext ?=> Unit): Unit =
  val debug = OrcaDebug.enabled || args.verbose.value
  // Per-run trace file: captures every stage, prompt, tool/subprocess call and
  // result at DEBUG. Started before anything logs so the whole run is caught;
  // surfaced to the console only on failure or with `--verbose` (see below).
  val orcaLog = OrcaLog.start()
  OrcaBanner.print(System.err, orcaLog.file)
  val flowLog = LoggerFactory.getLogger("orca.flow")
  flowLog.info("orca {} starting (workDir={})", OrcaBanner.version, workDir)
  flowLog.info("user prompt: {}", args.userPrompt)
  // A daemon thread or unsupervised fork that throws would otherwise
  // disappear with no diagnostic. Route every uncaught throwable to
  // stderr with its stack so a silent exit always leaves a trail.
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
            git = git,
            gh = gh,
            fs = fs,
            prompts = prompts
          )
          body(using ctx)
        finally effectiveInteraction.close()
    catch
      // Stage-level Errors have already been formatted and emitted
      // through the channel; the user saw a friendly message there. We
      // still log the unfriendly bits to stderr so a silent `exit 1` is
      // never a possibility — full stack for unexpected throwables, just
      // the message for OrcaFlowException unless debug is on. The trace
      // file is dumped here (System.exit skips the outer finally) so a
      // failure always leaves the full execution trail on the console.
      case NonFatal(e) =>
        flowLog.error("flow aborted: {}", e.getMessage, e)
        reportUncaught(e, debug)
        // The `System.exit(1)` below halts the JVM and skips the outer
        // `finally`, so the summary + trace-dump here run exactly once on this
        // path (and the failure always leaves the full trace on the console).
        costTracker.printSummary()
        orcaLog.finish(System.err, dump = true)
        System.exit(1)
  finally
    costTracker.printSummary()
    // Success (or a fatal escape): dump the trace only under `--verbose`;
    // otherwise just print its path. Idempotent — the error path above
    // already finished it before exiting.
    orcaLog.finish(System.err, dump = debug)

private def installUncaughtExceptionHandler(): Unit =
  // Idempotent across nested or repeated `flow(...)` calls — we only
  // install our handler if no app-specific one is already in place.
  if Thread.getDefaultUncaughtExceptionHandler == null then
    Thread.setDefaultUncaughtExceptionHandler: (thread, throwable) =>
      System.err.println(
        s"[orca] uncaught exception on thread '${thread.getName}':"
      )
      throwable.printStackTrace(System.err)

private def reportUncaught(e: Throwable, debug: Boolean): Unit =
  e match
    case _: OrcaFlowException =>
      // The stage's Error event already surfaced the message. Only
      // dump the stack on explicit debug; flow-level failures don't
      // need a JVM trace by default.
      if debug then e.printStackTrace(System.err)
    case _ =>
      // Anything that isn't an OrcaFlowException is unexpected — print
      // the stack unconditionally so silent exits are impossible.
      System.err.println(
        s"[orca] flow aborted by uncaught ${e.getClass.getName}:"
      )
      e.printStackTrace(System.err)
