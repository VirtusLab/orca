package orca.tools.claude

import orca.llm.{BackendTag, SessionId}
import orca.events.{Usage}
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.tools.claude.streamjson.RawJson

import orca.backend.LlmResult

/** Subset of the JSON Claude Code emits when invoked with `--output-format
  * json`. Field names mirror the CLI output.
  */
private[claude] case class ClaudeHeadlessResponse(
    session_id: String,
    result: String,
    usage: ClaudeUsage,
    total_cost_usd: Option[BigDecimal] = None,
    is_error: Option[Boolean] = None,
    /** Claude reports the resolved model name as the KEY of `modelUsage` —
      * `{"claude-sonnet-4-6": {...usage...}}`. There's no top-level `model`
      * field on the headless result, so this is where we read it from. In
      * practice exactly one entry per turn; the first key wins.
      */
    modelUsage: Option[Map[String, RawJson]] = None
) derives ConfiguredJsonValueCodec:

  def toLlmResult: LlmResult[BackendTag.ClaudeCode.type] =
    LlmResult(
      sessionId = SessionId[BackendTag.ClaudeCode.type](session_id),
      output = result,
      usage = Usage(
        inputTokens = usage.totalInputTokens,
        outputTokens = usage.output_tokens,
        cost = total_cost_usd,
        cachedInputTokens = usage.cachedInputTokens
      ),
      model = modelUsage.flatMap(_.keys.headOption)
    )

/** Claude Code splits an input-token count across three fields when prompt
  * caching is on (which it is by default): `input_tokens` covers only the new
  * uncached portion of the turn, while the system prompt + tool defs go into
  * `cache_creation_input_tokens` on the first turn and
  * `cache_read_input_tokens` on every subsequent turn. The total billable input
  * is the sum; the cached portion is `cache_creation + cache_read`.
  */
private[claude] case class ClaudeUsage(
    input_tokens: Long,
    output_tokens: Long,
    cache_creation_input_tokens: Option[Long] = None,
    cache_read_input_tokens: Option[Long] = None
) derives ConfiguredJsonValueCodec:
  def cachedInputTokens: Long =
    cache_creation_input_tokens.getOrElse(0L) +
      cache_read_input_tokens.getOrElse(0L)
  def totalInputTokens: Long = input_tokens + cachedInputTokens
