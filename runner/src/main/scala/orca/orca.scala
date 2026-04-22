package orca

import _root_.orca.runner.DefaultFlowContext
import _root_.orca.runner.terminal.{OrcaArgs, TerminalInteraction}
import ox.supervised

/** Entry point for flow scripts. Creates a FlowContext, registers the
  * interaction's listeners plus any extras the caller provides, and runs `body`
  * inside an Ox `supervised` scope so any forked effects are tied to the flow's
  * lifetime.
  *
  * Most scripts use:
  * ```
  * orca:
  *   val plan = claude.result[Plan].prompt(userPrompt)
  *   ...
  * ```
  *
  * Any tool can be overridden for testing or custom wiring, e.g. `orca(git =
  * Some(MyGit()), interaction = SlackInteraction(...))`.
  */
def orca(
    args: OrcaArgs = OrcaArgs(),
    interaction: Interaction = new TerminalInteraction(),
    extraListeners: List[OrcaListener] = Nil,
    workDir: os.Path = os.pwd,
    claude: Option[ClaudeTool] = None,
    git: Option[GitTool] = None,
    gh: Option[GitHubTool] = None,
    fs: Option[FsTool] = None,
    promptTemplate: PromptTemplate = DefaultPromptTemplate
)(body: FlowContext ?=> Unit): Unit =
  supervised:
    val dispatcher =
      new EventDispatcher(interaction.listeners ++ extraListeners)
    val ctx = DefaultFlowContext.withDefaults(
      userPrompt = args.userPrompt,
      dispatcher = dispatcher,
      workDir = workDir,
      claude = claude,
      git = git,
      gh = gh,
      fs = fs,
      template = promptTemplate
    )
    body(using ctx)
