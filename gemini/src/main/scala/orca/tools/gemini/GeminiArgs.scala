package orca.tools.gemini

import orca.backend.CliArgs
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

/** Maps `AgentConfig` fields to `gemini` headless CLI flags. `systemPrompt` is
  * not handled here — gemini has no `--append-system-prompt` equivalent, so the
  * backend folds it into the user prompt first. The `ask_user` MCP server is
  * registered via a project-local `.gemini/settings.json` merge in the backend,
  * not an argv flag.
  *
  * gemini headless is one-shot: each `-p` invocation processes one prompt and
  * exits. Multi-turn happens via `--resume <session-id>`, where the id comes
  * from the `init` event of a prior `stream-json` run — see [[headless]] /
  * [[resume]].
  */
private[gemini] object GeminiArgs:

  /** Single-turn `gemini -p <prompt> --output-format stream-json` invocation.
    * cwd is set on the OS process spawn (gemini headless has no `-C` flag), so
    * it isn't rendered here.
    */
  def headless(prompt: String, config: AgentConfig): Seq[String] =
    Seq("gemini") ++
      trustArgs ++
      CliArgs.modelArgs(config) ++
      approvalWiring(config.tools, config.autoApprove).args ++
      Seq("--output-format", "stream-json", "-p", prompt)

  /** Multi-turn continuation: `gemini --resume <id> -p <prompt>`. The id is the
    * session id learned from the prior run's `init` event.
    */
  def resume(
      sessionId: WireSessionId[BackendTag.Gemini.type],
      prompt: String,
      config: AgentConfig
  ): Seq[String] =
    Seq("gemini") ++
      trustArgs ++
      CliArgs.modelArgs(config) ++
      approvalWiring(config.tools, config.autoApprove).args ++
      Seq("--resume", WireSessionId.value(sessionId)) ++
      Seq("--output-format", "stream-json", "-p", prompt)

  /** gemini refuses to run headless in a folder it doesn't consider "trusted"
    * (exit 55) and silently overrides `--approval-mode` back to `default`
    * before failing. orca always drives a working directory the agent is meant
    * to operate in, so trust is unconditional.
    */
  private val trustArgs: Seq[String] = Seq("--skip-trust")

  /** Web tools pre-approved on [[ToolSet.NetworkOnly]] turns. Plan mode gates
    * `web_fetch` behind an approval no autonomous turn can answer; listing it
    * in `--allowed-tools` pre-approves it, so the planner gets web reads.
    * (`--allowed-tools` is deprecated for a `settings.json` Policy Engine in
    * gemini 1.0.)
    */
  private val NetworkTools: Seq[String] = Seq("web_fetch")

  private case class ApprovalWiring(args: Seq[String], cell: EnforcementCell)

  /** The read-only tiers' cell. Shared because both ride on `--approval-mode
    * plan`; `NetworkOnly` only pre-approves web reads on top. It records what
    * orca can stand behind rather than what the flag was assumed to do, so the
    * rationale carries the whole argument — this is its only home.
    */
  private val PlanModeCell: EnforcementCell = EnforcementCell(
    Enforcement.PromptOnly,
    "`--approval-mode plan` is UNMEASURED, not known weak: no headless `plan` turn has been run against a write attempt. The same class of mechanism on claude — `--permission-mode plan` — was measured and removes no tools (`docs/research/run-cost/09-diff-vs-coordinates.md` §2). Raise this cell when a probe establishes more."
  )

  /** Maps [[AgentConfig.tools]] to gemini's approval mode, and classifies how
    * strongly it holds. `Full` has no per-tool CLI allowlist, and in headless
    * mode `auto_edit` blocks on shell approvals no one can answer, so both
    * [[AutoApprove.All]] and [[AutoApprove.Only]] map to `yolo` (the `Only`
    * widening: ADR 0015) — the same flag, but not the same guarantee.
    */
  private def approvalWiring(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): ApprovalWiring =
    tools match
      case ToolSet.ReadOnly =>
        ApprovalWiring(Seq("--approval-mode", "plan"), PlanModeCell)
      case ToolSet.NetworkOnly =>
        ApprovalWiring(
          Seq(
            "--approval-mode",
            "plan",
            "--allowed-tools",
            NetworkTools.mkString(",")
          ),
          PlanModeCell
        )
      case ToolSet.Full =>
        val yolo = Seq("--approval-mode", "yolo")
        autoApprove match
          case AutoApprove.All =>
            ApprovalWiring(
              yolo,
              EnforcementCell(
                Enforcement.Hard,
                "`yolo` honours \"approve everything\" verbatim"
              )
            )
          case AutoApprove.Only(_) =>
            ApprovalWiring(
              yolo,
              EnforcementCell(
                Enforcement.Ignored,
                "no per-tool allowlist, so any `Only` set is widened to `yolo` — the requested subset reaches the CLI nowhere"
              )
            )

  /** How strongly gemini enforces each `(tools, autoApprove)` combination — see
    * [[approvalWiring]], which derives this from the same match that builds the
    * flags.
    */
  def enforcementCell(
      tools: ToolSet,
      autoApprove: AutoApprove,
      dispatch: TurnDispatch
  ): EnforcementCell = dispatch match
    // Same either way: [[resume]] carries the approval flags as [[headless]]
    // does. Matched rather than ignored so a new dispatch has to answer here.
    case TurnDispatch.Fresh | TurnDispatch.Resumed =>
      approvalWiring(tools, autoApprove).cell
