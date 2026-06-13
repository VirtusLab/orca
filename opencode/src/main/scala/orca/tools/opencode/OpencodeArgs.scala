package orca.tools.opencode

import orca.backend.{SessionMode, SystemPromptComposer}
import orca.llm.{LlmConfig, Model, ToolSet}
import orca.tools.opencode.OpencodeApi.{
  MessageBody,
  MessagePart,
  ModelRef,
  OutputFormat
}
import orca.util.RawJson

/** Maps an [[orca.llm.LlmConfig]] onto OpenCode's wire shapes: the `serve`
  * launch argv and the per-turn message body (ADR 0014).
  *
  * Unlike the subprocess backends, almost everything travels in the request
  * body rather than on a CLI flag: model, system prompt, output schema, and the
  * per-tool gate all live on [[MessageBody]]. `autoApprove` is an
  * approval-policy concern handled at the permission layer (the
  * `permission.asked` reply), not encoded here.
  */
private[opencode] object OpencodeArgs:

  /** `opencode serve` launch args. The `launcher` prefix (default: the bare
    * `opencode` binary, resolved from `PATH`) is followed by `serve …`; an
    * alternative like [[OpencodeLauncher.ollama]] wraps the binary to inject
    * provider config. Port 0 = an OS-assigned free port (read back from the
    * server's "listening on …" line). `--pure` is deliberately omitted so the
    * spawned server inherits the user's configured providers.
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
    */
  def message(
      config: LlmConfig,
      prompt: String,
      outputSchema: Option[String],
      mode: SessionMode
  ): MessageBody =
    MessageBody(
      parts = List(MessagePart("text", prompt)),
      model = config.model.map(toModelRef),
      system = SystemPromptComposer.combine(config),
      tools = toolFlags(config, mode),
      format = outputSchema.map(s => OutputFormat("json_schema", RawJson(s)))
    )

  private def toModelRef(model: Model): ModelRef =
    val (provider, id) = OpencodeModel.split(model)
    ModelRef(provider, id)

  /** Per-turn tool gate: disable the write tools on a read-only turn, and the
    * `question` tool on an autonomous turn. Returns `None` when nothing is
    * gated so the body omits `tools` and the server's defaults apply.
    *
    * Both read-only tiers disable the same write tools, so `NetworkOnly`
    * behaves like `ReadOnly` here — opencode gets no dedicated network
    * handling. opencode's web tool isn't in the disabled set, so web reads may
    * remain available (server-dependent; not verified here); shell `gh` stays
    * off (`bash` disabled). Scoped network would require enabling `bash`
    * (dropping the hard no-edit guarantee) — out of scope; opencode flows
    * pre-fetch issue/PR context instead.
    */
  private def toolFlags(
      config: LlmConfig,
      mode: SessionMode
  ): Option[Map[String, Boolean]] =
    val writeGate =
      config.tools match
        case ToolSet.ReadOnly | ToolSet.NetworkOnly =>
          Map(
            "write" -> false,
            "edit" -> false,
            "bash" -> false,
            "patch" -> false
          )
        case ToolSet.Full => Map.empty[String, Boolean]
    val question = mode match
      case SessionMode.Autonomous     => Map("question" -> false)
      case SessionMode.Interactive(_) => Map.empty[String, Boolean]
    // The two key sets are disjoint, so the merge order is irrelevant.
    val flags = writeGate ++ question
    Option.when(flags.nonEmpty)(flags)
