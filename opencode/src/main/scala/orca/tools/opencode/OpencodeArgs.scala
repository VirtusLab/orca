package orca.tools.opencode

import orca.backend.{SessionMode, SystemPromptComposer}
import orca.llm.{LlmConfig, Model}
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

  /** The `opencode` executable; resolved from `PATH`. */
  val ServeBinary: String = "opencode"

  /** `opencode serve` launch args. Port 0 = an OS-assigned free port (read back
    * from the server's "listening on …" line). `--pure` is deliberately omitted
    * so the spawned server inherits the user's configured providers.
    */
  def serve(port: Int = 0): Seq[String] =
    Seq(ServeBinary, "serve", "--port", port.toString, "--log-level", "WARN")

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
    */
  private def toolFlags(
      config: LlmConfig,
      mode: SessionMode
  ): Option[Map[String, Boolean]] =
    val readOnly =
      if config.readOnly then
        Map(
          "write" -> false,
          "edit" -> false,
          "bash" -> false,
          "patch" -> false
        )
      else Map.empty[String, Boolean]
    val question = mode match
      case SessionMode.Autonomous     => Map("question" -> false)
      case SessionMode.Interactive(_) => Map.empty[String, Boolean]
    // The two key sets are disjoint, so the merge order is irrelevant.
    val flags = readOnly ++ question
    Option.when(flags.nonEmpty)(flags)
