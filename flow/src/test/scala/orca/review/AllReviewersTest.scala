package orca.review

import orca.agents.{Announce, BackendTag, JsonData, AgentCall, Agent}
class AllReviewersTest extends munit.FunSuite:

  /** Agent that records every `withSystemPrompt` call into a shared buffer (so
    * renamed copies still feed the same record) and otherwise behaves as a
    * no-op stub. `withName` returns a fresh instance carrying the new name so
    * `allReviewers` can tag each reviewer.
    */
  private class RecordingTool(
      name: String = "base",
      systemPromptsSeen: collection.mutable.ListBuffer[String] =
        collection.mutable.ListBuffer.empty
  ) extends StubAgent(name):
    def seen: List[String] = systemPromptsSeen.toList
    override def withSystemPrompt(
        p: String
    ): Agent[BackendTag.ClaudeCode.type] =
      val _ = systemPromptsSeen += p
      this
    override def withName(n: String): Agent[BackendTag.ClaudeCode.type] =
      new RecordingTool(n, systemPromptsSeen)
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] =
      ???

  test("allReviewers exposes the full canonical reviewer set"):
    val base = new RecordingTool
    val names = allReviewers(base).map(_.name)
    assertEquals(names, ReviewerPrompts.all.map(_.name))

  test("each reviewer layers its canonical system prompt onto the base tool"):
    val base = new RecordingTool
    val _ = allReviewers(base)
    assertEquals(base.seen, ReviewerPrompts.all.map(_.systemPrompt))

  test("each reviewer's description is non-empty (parsed from frontmatter)"):
    ReviewerPrompts.all.foreach: r =>
      assert(
        r.description.nonEmpty,
        s"reviewer '${r.name}' has an empty description"
      )

  test("minimalReviewers exposes the small subset"):
    val base = new RecordingTool
    val names = minimalReviewers(base).map(_.name)
    assertEquals(
      names,
      ReviewerPrompts.minimal.map(_.name)
    )

  test("SelectedReviewers.pick filters the reviewer list by name"):
    val base = new RecordingTool
    val all =
      allReviewers(base).zipWithIndex.map((a, i) =>
        new RosterEntry(a, ReviewerId(i))
      )
    val picked =
      SelectedReviewers(List("performance", "code-structure")).pick(all)
    assertEquals(
      picked.entries.map(_.name),
      List("code-structure", "performance")
    )
