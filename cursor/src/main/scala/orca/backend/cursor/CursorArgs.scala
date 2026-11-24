package orca.backend.cursor

import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  EnforcementCell,
  WireSessionId,
  ToolSet,
  TurnDispatch
}
import orca.backend.CliArgs

/** Maps `AgentConfig` to `cursor-agent` headless argv for `--print
  * --output-format stream-json`. The prompt is the final positional argument.
  * Multi-turn uses `--resume <session-id>` from the prior turn's init event.
  */
private[cursor] object CursorArgs:

  private val Binary: String = BackendTag.Cursor.cliBinary

  def headless(prompt: String, config: AgentConfig): Seq[String] =
    base(config) :+ prompt

  def resume(
      sessionId: WireSessionId[BackendTag.Cursor.type],
      prompt: String,
      config: AgentConfig
  ): Seq[String] =
    base(config) ++ Seq("--resume", WireSessionId.value(sessionId)) :+ prompt

  private def base(config: AgentConfig): Seq[String] =
    Seq(
      Binary,
      "-p",
      "--output-format",
      "stream-json"
    ) ++ CliArgs.modelArgs(config) ++ modeWiring(
      config.tools,
      config.autoApprove
    ).args

  private case class ModeWiring(args: Seq[String], cell: EnforcementCell)

  private val PlanModeCell: EnforcementCell = EnforcementCell(
    Enforcement.PromptOnly,
    "`--plan` is read-only on paper; write-capable tools are not removed from the CLI, so only the prompt withholds edits"
  )

  private def modeWiring(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): ModeWiring =
    tools match
      case ToolSet.ReadOnly | ToolSet.NetworkOnly | ToolSet.NoTools =>
        ModeWiring(Seq("--plan"), PlanModeCell)
      case ToolSet.Full =>
        autoApprove match
          case AutoApprove.All =>
            ModeWiring(
              Seq("--force"),
              EnforcementCell(
                Enforcement.Hard,
                "`--force` allows tool runs without interactive approval"
              )
            )
          case AutoApprove.Only(_) =>
            ModeWiring(
              Seq("--force"),
              EnforcementCell(
                Enforcement.Ignored,
                "no per-tool allowlist on cursor-agent; `Only` widens to `--force`"
              )
            )

  def enforcementCell(
      tools: ToolSet,
      autoApprove: AutoApprove,
      dispatch: TurnDispatch
  ): EnforcementCell = dispatch match
    case TurnDispatch.Fresh | TurnDispatch.Resumed =>
      modeWiring(tools, autoApprove).cell
