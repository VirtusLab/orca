package orca.review

import orca.agents.{Announce, BackendTag, JsonData, AgentCall, Agent}
class AllReviewersTest extends munit.FunSuite:

  // `allReviewers`/`minimalReviewers` read the run's catalog; the default one
  // is the shipped set.
  private given orca.FlowContext =
    new orca.TestFlowContext(new orca.events.EventDispatcher(Nil))

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
    val reviewers = allReviewers(base)
    val names = ReviewerPrompts.all.map(_.name)
    assertEquals(reviewers.map(_.definition.name), names)
    // The agent is renamed too, so the run bills each reviewer separately.
    assertEquals(reviewers.map(_.agent.name), names)

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

  test("the presets build from the run's catalog, not the shipped set"):
    val extra =
      Reviewer("orca", "checks orca's own rules", systemPrompt = "## Scope")
    // Shadows the file-level built-in context for this test only.
    given orca.FlowContext = new orca.TestFlowContext(
      new orca.events.EventDispatcher(Nil),
      reviewerCatalog = new ReviewerCatalog(
        List(DiscoveredReviewer(extra, ReviewerFileTier.Project, Nil))
      )
    )
    val base = new RecordingTool
    assertEquals(
      allReviewers(base).map(_.definition.name),
      ReviewerPrompts.all.map(_.name) :+ "orca"
    )
    assertEquals(
      minimalReviewers(new RecordingTool).map(_.definition.name),
      ReviewerPrompts.minimal.map(_.name) :+ "orca"
    )
    assert(base.seen.contains("## Scope"), base.seen.toString)

  test("a reviewer cannot be built with a blank description"):
    // The picker is handed `- <name>: <description>`; a blank one leaves it
    // guessing from the slug for the whole loop.
    val _ = intercept[orca.OrcaFlowException](
      Reviewer("my-thing", " ", systemPrompt = "…")
    )
