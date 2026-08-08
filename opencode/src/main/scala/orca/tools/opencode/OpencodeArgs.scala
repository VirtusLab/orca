package orca.tools.opencode

import orca.backend.{ConversationMode, SystemPromptComposer}
import orca.agents.{
  AgentConfig,
  AutoApprove,
  Enforcement,
  EnforcementCell,
  Model,
  ToolSet,
  TurnDispatch
}
import orca.tools.opencode.OpencodeApi.{
  MessageBody,
  MessagePart,
  ModelRef,
  OutputFormat
}
import orca.util.RawJson

/** Maps an [[orca.agents.AgentConfig]] onto OpenCode's wire shapes: the `serve`
  * launch argv and the per-turn message body (ADR 0014).
  *
  * Unlike the subprocess backends, almost everything travels in the request
  * body rather than on a CLI flag: model, system prompt, output schema, and the
  * per-tool gate all live on [[MessageBody]]. `autoApprove` is not encoded at
  * all — see [[enforcementCell]].
  */
private[opencode] object OpencodeArgs:

  /** `opencode serve` launch args: the `launcher` prefix followed by `serve …`.
    * Port 0 = an OS-assigned free port (read back from the server's "listening
    * on …" line). `--pure` is deliberately omitted so the spawned server
    * inherits the user's configured providers.
    */
  def serve(
      launcher: OpencodeLauncher = OpencodeLauncher.default,
      port: Int = 0
  ): Seq[String] =
    launcher.prefix ++
      Seq("serve", "--port", port.toString, "--log-level", "WARN")

  /** Assemble the body for `POST …/prompt_async`. `model = None` omits the
    * field so the server falls back to its configured default. `outputSchema`
    * (when set) enforces structured output via `format`. `mode` gates the
    * native `question` tool — disabled on autonomous turns, where nobody can
    * answer.
    *
    * `system` AUGMENTS the server's own system prompt rather than replacing it
    * — measured against opencode 1.17.10 by asking a model to describe its
    * instructions with and without the field: with it set, the built-in prompt
    * is intact ("You are opencode, an interactive CLI tool…", tool policy,
    * environment) and the supplied text is appended at the end. So sending it
    * unconditionally costs the agent none of its defaults.
    */
  def message(
      config: AgentConfig,
      prompt: String,
      outputSchema: Option[String],
      mode: ConversationMode
  ): MessageBody =
    MessageBody(
      parts = List(MessagePart("text", prompt)),
      model = config.model.map(toModelRef),
      system = Some(SystemPromptComposer.combine(config)),
      // orca never targets a specific opencode agent profile — omitted so the
      // server's default applies.
      agent = None,
      tools = toolFlags(config, mode),
      format = outputSchema.map(s => OutputFormat("json_schema", RawJson(s)))
    )

  private def toModelRef(model: Model): ModelRef =
    val (provider, id) = OpencodeModel.split(model)
    ModelRef(provider, id)

  /** The tools both restricted tiers withhold. `task` is one of them: it spawns
    * a subagent that runs with the agent profile's default, write-capable tool
    * set, and whether these per-message flags reach a subagent is unmeasured.
    * `todowrite` is not: it writes the agent's own todo state, not the
    * workspace.
    */
  private val restrictedToolsOff: Map[String, Boolean] =
    Map(
      "write" -> false,
      "edit" -> false,
      "bash" -> false,
      "patch" -> false,
      "task" -> false
    )

  /** The tool flags a tier's message body carries and the guarantee they
    * achieve. The `question` gate is not here: it follows the conversation
    * mode, not the tier.
    */
  private case class TierWiring(
      flags: Map[String, Boolean],
      cell: EnforcementCell
  )

  /** Maps [[AgentConfig.tools]] to the per-turn tool gate, and classifies how
    * strongly it holds.
    *
    * `webfetch` reads the network without a shell, which is what separates the
    * two restricted tiers: `ReadOnly` disables it by name, `NetworkOnly` leaves
    * it unset so the server's own default decides. Neither tier has `bash`, so
    * a `NetworkOnly` turn cannot run `gh`.
    */
  private def tierWiring(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): TierWiring =
    tools match
      case ToolSet.ReadOnly =>
        TierWiring(
          restrictedToolsOff + ("webfetch" -> false),
          EnforcementCell(
            Enforcement.Hard,
            "the message body disables `write`, `edit`, `bash`, `patch`, `task` and `webfetch` by name, so the server offers none of them — a denylist, so it stays exact only while opencode ships no further writing or network tool"
          )
        )
      case ToolSet.NetworkOnly =>
        TierWiring(
          restrictedToolsOff,
          EnforcementCell(
            Enforcement.Hard,
            "the message body disables `write`, `edit`, `bash`, `patch` and `task` by name and leaves `webfetch` to the server default, so the turn reads the web where the server offers it but has no `bash` to run `gh` — a denylist, so it stays exact only while opencode ships no further writing tool"
          )
        )
      case ToolSet.Full =>
        autoApprove match
          case AutoApprove.All | AutoApprove.Only(_) =>
            TierWiring(
              Map.empty,
              EnforcementCell(
                Enforcement.Ignored,
                "the approval policy is whatever the user's `opencode` server config answers a `permission.asked` with, outside orca's control (ADR 0014 risk)"
              )
            )

  /** Per-turn tool gate: the tier's gate from [[tierWiring]], plus the
    * `question` tool disabled on an autonomous turn. Returns `None` when
    * nothing is gated so the body omits `tools` and the server's defaults
    * apply.
    */
  private def toolFlags(
      config: AgentConfig,
      mode: ConversationMode
  ): Option[Map[String, Boolean]] =
    val question =
      if mode.isInteractive then Map.empty[String, Boolean]
      else Map("question" -> false)
    // The two key sets are disjoint, so the merge order is irrelevant.
    val flags = tierWiring(config.tools, config.autoApprove).flags ++ question
    Option.when(flags.nonEmpty)(flags)

  /** How strongly opencode enforces each `(tools, autoApprove)` combination —
    * see [[tierWiring]], which derives this from the same match that builds the
    * gate.
    */
  def enforcementCell(
      tools: ToolSet,
      autoApprove: AutoApprove,
      dispatch: TurnDispatch
  ): EnforcementCell = dispatch match
    // Same either way: the gate rides on [[message]], which every turn sends.
    // Matched rather than ignored so a new dispatch has to answer here.
    case TurnDispatch.Fresh | TurnDispatch.Resumed =>
      tierWiring(tools, autoApprove).cell
