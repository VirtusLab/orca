package orca

import _root_.orca.backend.Interaction
import _root_.orca.io.DefaultPrompts
import _root_.orca.runner.DefaultFlowContext
import _root_.orca.runner.terminal.TerminalInteraction
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
    prompts: Prompts = DefaultPrompts
)(body: FlowContext ?=> Unit): Unit =
  val debug = OrcaDebug.enabled || args.verbose.value
  // A daemon thread or unsupervised fork that throws would otherwise
  // disappear with no diagnostic. Route every uncaught throwable to
  // stderr with its stack so a silent exit always leaves a trail.
  installUncaughtExceptionHandler()
  // Default to a TerminalInteraction parameterised with the resolved
  // `workDir` — Scala 3 default-arg evaluation can't see prior params
  // in the same list, so the substitution happens here instead.
  val effectiveInteraction = interaction.getOrElse(
    new TerminalInteraction(workDir = Some(workDir))
  )
  // Always tally token usage; print the summary on exit (success or failure)
  // so the user sees what was spent before the process terminates. Callers
  // can still pass their own CostTracker via `extraListeners` for other uses
  // — it'll observe the same events independently.
  val costTracker = new CostTracker
  // `try/finally` so the cost summary always lands — even when a fatal
  // throwable (OOM, StackOverflow) escapes the NonFatal catch below.
  // Tokens may have already been spent; the user deserves to see what.
  try
    try
      supervised:
        val dispatcher = new EventDispatcher(
          effectiveInteraction.listeners ++ List(costTracker) ++ extraListeners
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
    catch
      // Stage-level Errors have already been formatted and emitted
      // through the channel; the user saw a friendly message there. We
      // still log the unfriendly bits to stderr so a silent `exit 1` is
      // never a possibility — full stack for unexpected throwables, just
      // the message for OrcaFlowException unless debug is on.
      case NonFatal(e) =>
        reportUncaught(e, debug)
        System.exit(1)
  finally costTracker.printSummary()

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
