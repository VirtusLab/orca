package orca

import _root_.orca.io.DefaultPrompts
import _root_.orca.runner.DefaultFlowContext
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import scala.util.control.NonFatal

/** Entry point for flow scripts. Takes the parsed CLI args (required) plus any
  * number of overrides, then runs the body inside an Ox `supervised` scope with
  * the resulting `FlowContext` as an ambient given.
  *
  * ```
  * flow(OrcaArgs(args)):
  *   val plan = claude.resultAs[Plan].autonomous(userPrompt)
  *   ...
  * ```
  *
  * With overrides:
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
  * `interaction` is `Option[Interaction]` (rather than a direct `Interaction`
  * with a default `new TerminalInteraction()`) so the default can be
  * parameterised by `workDir`, which Scala 3's default-arg evaluation can't see
  * across params in the same list. Pass `Some(myInteraction)` to override;
  * leave it out and the resolved `workDir` flows into the default
  * `TerminalInteraction`.
  *
  * The two-parameter-list shape is deliberate: Scala 3's fewer-braces
  * propagates the `FlowContext ?=>` given into the body only when the block
  * lands in a list of its own. `flow(...): body` satisfies that because the
  * first list is closed by `(...)` before the block starts.
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
  // Default to a TerminalInteraction parameterised with the resolved
  // `workDir` — Scala 3 default-arg evaluation can't see prior params
  // in the same list, so the substitution happens here instead.
  val effectiveInteraction = interaction.getOrElse(
    new TerminalInteraction(workDir = Some(workDir))
  )
  try
    supervised:
      val dispatcher =
        new EventDispatcher(effectiveInteraction.listeners ++ extraListeners)
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
    // Stage-level Errors have already been emitted through the channel.
    // The outer `catch` exists to suppress the raw JVM stack trace on
    // exit — the user has already seen a formatted message. With
    // `ORCA_DEBUG=1` or `--verbose` we do print the trace for
    // diagnostics.
    case NonFatal(e) =>
      if debug then e.printStackTrace()
      System.exit(1)
