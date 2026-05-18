package orca.review

import orca.{
  Announce,
  AutonomousTextCall,
  Backend,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool
}

class DefaultReviewersTest extends munit.FunSuite:

  /** LlmTool that records every `withSystemPrompt` call into a shared buffer
    * (so renamed copies still feed the same record) and otherwise behaves as a
    * no-op stub. `withName` returns a fresh instance carrying the new name so
    * `defaultReviewers` can tag each reviewer.
    */
  private class RecordingTool(
      val name: String = "base",
      systemPromptsSeen: collection.mutable.ListBuffer[String] =
        collection.mutable.ListBuffer.empty
  ) extends LlmTool[Backend.ClaudeCode.type]:
    def seen: List[String] = systemPromptsSeen.toList
    def autonomous: AutonomousTextCall[Backend.ClaudeCode.type] = ???
    def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
    def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] =
      val _ = systemPromptsSeen += p
      this
    def withName(n: String): LlmTool[Backend.ClaudeCode.type] =
      new RecordingTool(n, systemPromptsSeen)
    def resultAs[O: JsonData: Announce]: LlmCall[Backend.ClaudeCode.type, O] =
      ???

  test(
    "defaultReviewers exposes the full canonical reviewer set"
  ):
    val base = new RecordingTool
    val names = defaultReviewers(base).map(_.name)
    assertEquals(
      names,
      List(
        "reviewer: performance",
        "reviewer: readability",
        "reviewer: test-coverage",
        "reviewer: code-functionality",
        "reviewer: abstraction"
      )
    )

  test("each reviewer layers its canonical system prompt onto the base tool"):
    val base = new RecordingTool
    val _ = defaultReviewers(base)
    val seen = base.seen
    assertEquals(
      seen,
      List(
        ReviewerPrompts.Performance,
        ReviewerPrompts.Readability,
        ReviewerPrompts.TestCoverage,
        ReviewerPrompts.CodeFunctionality,
        ReviewerPrompts.Abstraction
      )
    )

  test("SelectedReviewers.pick filters the default list by name"):
    val base = new RecordingTool
    val all = defaultReviewers(base)
    val picked =
      SelectedReviewers(List("reviewer: performance", "reviewer: test-coverage"))
        .pick(all)
    assertEquals(
      picked.map(_.name),
      List("reviewer: performance", "reviewer: test-coverage")
    )
