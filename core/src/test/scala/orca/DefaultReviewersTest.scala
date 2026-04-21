package orca

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

class DefaultReviewersTest extends munit.FunSuite:

  /** LlmTool that records every `withSystemPrompt` call and delegates the rest
    * so tests can inspect which system prompts defaultReviewers layers on.
    */
  private class RecordingTool extends LlmTool[Backend.ClaudeCode.type]:
    val name = "base"
    var systemPromptsSeen: List[String] = Nil
    def ask(p: String, c: LlmConfig = LlmConfig.default): String = ""
    def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
    def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] =
      systemPromptsSeen = p :: systemPromptsSeen
      this
    def result[O: Schema: ConfiguredJsonValueCodec]
        : LlmCall[Backend.ClaudeCode.type, O] = ???

  test(
    "defaultReviewers exposes performance, readability, and test-coverage names"
  ):
    val base = new RecordingTool
    val names = defaultReviewers(base).map(_.name)
    assertEquals(names, List("performance", "readability", "test-coverage"))

  test("each reviewer layers its canonical system prompt onto the base tool"):
    val base = new RecordingTool
    val _ = defaultReviewers(base)
    val seen = base.systemPromptsSeen.reverse
    assertEquals(
      seen,
      List(
        ReviewerPrompts.Performance,
        ReviewerPrompts.Readability,
        ReviewerPrompts.TestCoverage
      )
    )

  test("SelectedReviewers.pick filters the default list by name"):
    val base = new RecordingTool
    val all = defaultReviewers(base)
    val picked =
      SelectedReviewers(List("performance", "test-coverage")).pick(all)
    assertEquals(picked.map(_.name), List("performance", "test-coverage"))
