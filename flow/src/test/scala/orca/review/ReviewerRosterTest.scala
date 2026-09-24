package orca.review

class ReviewerRosterTest extends munit.FunSuite:

  private given orca.FlowContext =
    new orca.TestFlowContext(new orca.events.EventDispatcher(Nil))

  test(
    "a roster entry reads its name, description and file pattern off the reviewer definition"
  ):
    val definition = Reviewer(
      name = ReviewerSlug("scala-fp"),
      description = "checks functional style",
      systemPrompt = "…",
      filePattern = Some("""\.scala$""".r)
    )
    // The agent is named differently so the assertions distinguish the two
    // sources; `buildReviewers` keeps the two names equal in production.
    val entry = new RosterEntry(
      ReviewerAgent(definition, new FakeAgent("unused-agent-name").agent),
      ReviewerId(0)
    )
    assertEquals(entry.name.value, "scala-fp")
    assertEquals(entry.description, definition.description)
    assertEquals(entry.filePattern.map(_.regex), Some("""\.scala$"""))

  test("SelectedReviewers.pick filters the reviewer list by name"):
    val all =
      allReviewers(new FakeAgent("base").agent).zipWithIndex.map((r, i) =>
        new RosterEntry(r, ReviewerId(i))
      )
    val picked =
      SelectedReviewers(List("performance", "code-structure")).pick(all)
    assertEquals(
      picked.entries.map(_.name.value),
      List("code-structure", "performance")
    )
