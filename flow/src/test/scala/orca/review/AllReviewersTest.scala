package orca.review

import orca.agents.{Agent, BackendTag}
import orca.testkit.{ScriptedBackend, TestAgent}
class AllReviewersTest extends munit.FunSuite:

  // `allReviewers`/`minimalReviewers` read the run's catalog; the default one
  // is the shipped set.
  private given orca.FlowContext =
    new orca.TestFlowContext(new orca.events.EventDispatcher(Nil))

  private def base: Agent[BackendTag.ClaudeCode.type] =
    TestAgent(ScriptedBackend.unused(BackendTag.ClaudeCode), "base")

  private def systemPrompts(
      reviewers: List[ReviewerAgent[BackendTag.ClaudeCode.type]]
  ): List[String] =
    reviewers.flatMap(_.agent.config.systemPrompt)

  test("allReviewers exposes the full canonical reviewer set"):
    val reviewers = allReviewers(base)
    val names = ReviewerPrompts.all.map(_.name.value)
    assertEquals(reviewers.map(_.definition.name.value), names)
    // The agent is renamed too, so the run bills each reviewer separately.
    assertEquals(reviewers.map(_.agent.name), names)

  test("each reviewer layers its canonical system prompt onto the base tool"):
    assertEquals(
      systemPrompts(allReviewers(base)),
      ReviewerPrompts.all.map(_.systemPrompt)
    )

  test("each reviewer's description is non-empty (parsed from frontmatter)"):
    ReviewerPrompts.all.foreach: r =>
      assert(
        r.description.nonEmpty,
        s"reviewer '${r.name}' has an empty description"
      )

  test("the presets build from the run's catalog, not the shipped set"):
    val extra =
      Reviewer(
        ReviewerSlug("orca"),
        "checks orca's own rules",
        systemPrompt = "## Scope"
      )
    // Shadows the file-level built-in context for this test only.
    given orca.FlowContext = new orca.TestFlowContext(
      new orca.events.EventDispatcher(Nil),
      reviewerCatalog = new ReviewerCatalog(
        List(DiscoveredReviewer(extra, ReviewerFileTier.Project, Nil))
      )
    )
    val all = allReviewers(base)
    assertEquals(
      all.map(_.definition.name),
      ReviewerPrompts.all.map(_.name) :+ "orca"
    )
    assertEquals(
      minimalReviewers(base).map(_.definition.name),
      ReviewerPrompts.minimal.map(_.name) :+ "orca"
    )
    assert(
      systemPrompts(all).contains("## Scope"),
      systemPrompts(all).toString
    )

  test("a reviewer cannot be built with a blank description"):
    // The picker is handed `- <name>: <description>`; a blank one leaves it
    // guessing from the slug for the whole loop.
    val _ = intercept[orca.OrcaFlowException](
      Reviewer(ReviewerSlug("my-thing"), " ", systemPrompt = "…")
    )
