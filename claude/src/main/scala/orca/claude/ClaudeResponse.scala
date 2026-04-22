package orca.claude

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.{Backend, LlmResult, SessionId, Usage}

/** Subset of the JSON Claude Code emits when invoked with `--output-format
  * json`. Field names mirror the CLI output.
  */
case class ClaudeHeadlessResponse(
    session_id: String,
    result: String,
    usage: ClaudeUsage,
    total_cost_usd: Option[BigDecimal] = None,
    is_error: Option[Boolean] = None
) derives ConfiguredJsonValueCodec:

  def toLlmResult: LlmResult[Backend.ClaudeCode.type] =
    LlmResult(
      sessionId = SessionId[Backend.ClaudeCode.type](session_id),
      output = result,
      usage = Usage(
        inputTokens = usage.input_tokens,
        outputTokens = usage.output_tokens,
        cost = total_cost_usd
      )
    )

case class ClaudeUsage(
    input_tokens: Long,
    output_tokens: Long
) derives ConfiguredJsonValueCodec
