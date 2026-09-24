package orca.review

import orca.{FlowContext, TestFlowContext}
import orca.events.EventDispatcher
import orca.agents.Agent
import orca.plan.Title

/** A picker replying `response` to each of up to two selection turns. */
private def recordingPicker(response: SelectedReviewers): FakeAgent =
  new FakeAgent("review", List(response, response))

/** The reviewer slugs the picker's first turn was offered, read off its
  * `Available reviewers:` list; `None` if the picker never ran.
  */
private def offered(picker: FakeAgent): Option[List[String]] =
  picker.seenPrompts.headOption.map(
    _.linesIterator
      .dropWhile(_ != "Available reviewers:")
      .drop(1)
      .takeWhile(_.startsWith("  - "))
      .map(_.stripPrefix("  - ").takeWhile(_ != ':'))
      .toList
  )

class ReviewerSelectorTest extends munit.FunSuite:

  private given FlowContext = new TestFlowContext(new EventDispatcher(Nil))

  // `agentDriven`'s effects are gated on `InStage` at `prepare` time; mint the
  // token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  private val scalaFp: RosterEntry =
    new RosterEntry(
      asReviewer(
        new FakeAgent("scala-fp"),
        filePattern = Some("""\.scala$""".r)
      ),
      ReviewerId(0)
    )
  private val generic: RosterEntry =
    new RosterEntry(asReviewer(new FakeAgent("generic")), ReviewerId(1))
  private val all: List[RosterEntry] = List(scalaFp, generic)

  /** A [[ReviewLoopFixture.StepCapture]] behind its own [[FlowContext]]. */
  private class SelectorSteps extends ReviewLoopFixture.StepCapture:
    val ctx: FlowContext = new TestFlowContext(dispatcher)

  private def reported(e: RosterEntry): ReviewBatch =
    ReviewBatch(List(e -> ReviewResult(List(finding("found something")))))

  test("file-pattern reviewers are dropped before the picker sees them"):
    val picker = recordingPicker(SelectedReviewers(List("scala-fp", "generic")))
    val selector = ReviewerSelector.agentDriven(agent = picker.agent)
    val picked =
      selector.prepare(all, Title("any"), List("src/lib.rs"))(Nil)
    // Even though the picker tried to include scala-fp, it was never offered
    // and the post-filter drops it from the result.
    assertEquals(picked.map(_.name.value), List("generic"))
    // The picker is shown bare slugs — no `reviewer: ` cost-attribution prefix
    // reaches it.
    assertEquals(
      offered(picker),
      Some(List("generic"))
    )

  test("the picker is shown each reviewer's own description"):
    // A reviewer the library does not ship: its description can only reach the
    // picker off its own definition.
    val picker =
      recordingPicker(SelectedReviewers(List("bespoke")))
    val bespoke = new RosterEntry(
      asReviewer(
        new FakeAgent("bespoke"),
        description = "checks the widget wiring"
      ),
      ReviewerId(0)
    )
    val _ = ReviewerSelector
      .agentDriven(agent = picker.agent)
      .prepare(List(bespoke), Title("any"), List("Widget.scala"))(Nil)
    assert(
      picker.seenPrompts.exists(
        _.contains("  - bespoke: checks the widget wiring")
      ),
      picker.seenPrompts.toString
    )

  test("picker reply resolves reviewers by bare slug"):
    val picker = recordingPicker(SelectedReviewers(List("generic", "scala-fp")))
    val selector = ReviewerSelector.agentDriven(agent = picker.agent)
    val picked =
      selector.prepare(all, Title("any"), List("src/main/scala/Foo.scala"))(Nil)
    assertEquals(
      picked.map(_.name.value).toSet,
      Set("generic", "scala-fp")
    )

  test(
    "empty picker selection falls back to all eligible (review never skipped)"
  ):
    val picker = recordingPicker(SelectedReviewers(Nil))
    val selector = ReviewerSelector.agentDriven(agent = picker.agent)
    // scala-fp is filtered out for a .rs change; generic is eligible. The
    // picker picks nothing, so the floor falls back to the eligible set.
    val picked =
      selector.prepare(all, Title("any"), List("src/lib.rs"))(Nil)
    assertEquals(picked.map(_.name.value), List("generic"))

  test("file-pattern reviewers are offered when matching files are present"):
    val picker = recordingPicker(SelectedReviewers(List("scala-fp", "generic")))
    val selector = ReviewerSelector.agentDriven(agent = picker.agent)
    val picked = selector.prepare(
      all,
      Title("any"),
      List("src/main/scala/Foo.scala")
    )(Nil)
    assertEquals(
      picked.map(_.name.value),
      List("scala-fp", "generic")
    )

  test("an empty diff keeps file-pattern reviewers eligible"):
    val picker = recordingPicker(SelectedReviewers(List("scala-fp")))
    val selector = ReviewerSelector.agentDriven(agent = picker.agent)
    // No changed files means the diff didn't say which files changed, not that
    // none did: the pre-filter is skipped, so scala-fp reaches the picker.
    val picked = selector.prepare(all, Title("any"), Nil)(Nil)
    assertEquals(
      offered(picker),
      Some(List("scala-fp", "generic"))
    )
    assertEquals(picked.map(_.name.value), List("scala-fp"))

  test("an empty diff announces the skipped file-pattern filter"):
    val capture = new SelectorSteps
    val selector = ReviewerSelector.agentDriven(agent =
      recordingPicker(SelectedReviewers(List("scala-fp"))).agent
    )
    val _ = selector.prepare(all, Title("any"), Nil)(using
      capture.ctx,
      summon[orca.InStage]
    )(Nil)
    assert(
      capture.messages.contains(
        "reviewer selection: no changed files were found; keeping " +
          "1 file-gated reviewer eligible (scala-fp)"
      ),
      capture.messages.mkString("\n")
    )

  test("a picker name that differs only in case still resolves"):
    val picker =
      recordingPicker(SelectedReviewers(List(" Scala-FP ")))
    val selector = ReviewerSelector.agentDriven(agent = picker.agent)
    val picked =
      selector.prepare(all, Title("any"), List("src/main/scala/Foo.scala"))(Nil)
    assertEquals(picked.map(_.name.value), List("scala-fp"))

  test("a partially-wrong pick announces the names it dropped"):
    // Without the announcement a single-character echo error removes a reviewer
    // from the whole loop with no event, and nothing downstream can restore it.
    val capture = new SelectorSteps
    val selector = ReviewerSelector.agentDriven(
      agent =
        recordingPicker(SelectedReviewers(List("generic", "scla-fp"))).agent
    )
    val picked = selector.prepare(all, Title("any"), List("Foo.scala"))(using
      capture.ctx,
      summon[orca.InStage]
    )(Nil)
    assertEquals(
      picked.map(_.name.value),
      List("generic"),
      "the resolvable half of the pick still runs"
    )
    assert(
      capture.messages.exists(
        _ == "reviewer selection: picker named scla-fp, matching no reviewer"
      ),
      capture.messages.mkString("\n")
    )

  test("the picker's turn is billed as a reviewer-role turn of its own"):
    val picker = recordingPicker(SelectedReviewers(Nil))
    val pickerCtx: FlowContext = new TestFlowContext(new EventDispatcher(Nil)):
      override lazy val reviewAgent: Agent[?] = picker.agent
    val _ = ReviewerSelector.agentDriven
      .prepare(all, Title("any"), List("src/main/scala/Foo.scala"))(using
        pickerCtx,
        summon[orca.InStage]
      )(Nil)
    assertEquals(
      picker.seenIdentities,
      List((ReviewerSelector.PickerName, Some(ReviewerPrompts.Role)))
    )

  test("selector skips the picker LLM entirely when no reviewer is eligible"):
    val picker = recordingPicker(SelectedReviewers(List("scala-fp")))
    val onlyScala = List(scalaFp)
    val selector = ReviewerSelector.agentDriven(agent = picker.agent)
    val picked =
      selector.prepare(onlyScala, Title("any"), List("src/lib.rs"))(Nil)
    assertEquals(picked, Nil)
    assertEquals(picker.seenPrompts, Nil)

  test(
    "agentDriven queries the picker exactly once per prepare, across many rounds"
  ):
    val picker = recordingPicker(SelectedReviewers(List("scala-fp", "generic")))
    val selector = ReviewerSelector.agentDriven(agent = picker.agent)
    // ONE prepare — the single LLM call happens here.
    val selectRound =
      selector.prepare(all, Title("any"), List("src/main/scala/Foo.scala"))
    // Apply the pure per-round function to three different histories, as the
    // loop would over successive rounds.
    val r1 = selectRound(Nil)
    val r2 = selectRound(List(ReviewBatch(Nil)))
    val r3 = selectRound(List(ReviewBatch(Nil), ReviewBatch(Nil)))
    assertEquals(
      r1.map(_.name.value),
      List("scala-fp", "generic")
    )
    assertEquals(r2, r1)
    assertEquals(r3, r1)
    assertEquals(picker.seenPrompts.size, 1)

  test("a selector value is reusable across loops"):
    val picker = recordingPicker(SelectedReviewers(List("scala-fp", "generic")))
    val selector = ReviewerSelector.agentDriven(agent = picker.agent)
    // Two loops reuse the same selector value — each `prepare` re-queries the
    // picker (fresh pick per loop, no cross-loop cache).
    val _ = selector.prepare(all, Title("loop-1"), List("a.scala"))(Nil)
    val _ = selector.prepare(all, Title("loop-2"), List("b.scala"))(Nil)
    assertEquals(picker.seenPrompts.size, 2)

  test("narrowing re-runs only the reviewers that reported last round"):
    val selector =
      ReviewerSelector.narrowingAcrossRounds(ReviewerSelector.allEveryRound)
    val selectRound = selector.prepare(all, Title("any"), List("src/lib.rs"))
    assertEquals(
      selectRound(Nil).map(_.name.value),
      List("scala-fp", "generic")
    )
    assertEquals(
      selectRound(List(reported(scalaFp))).map(_.name.value),
      List("scala-fp")
    )

  test("narrowing never empties the active set"):
    val capture = new SelectorSteps
    val selector =
      ReviewerSelector.narrowingAcrossRounds(ReviewerSelector.allEveryRound)
    val selectRound = selector.prepare(all, Title("any"), List("src/lib.rs"))(
      using
      capture.ctx,
      summon[orca.InStage]
    )
    // Nobody reported. A lint gate can keep the fix loop iterating through
    // that, so the round must not run zero reviewers.
    assertEquals(
      selectRound(List(ReviewBatch(Nil))).map(_.name.value),
      List("scala-fp", "generic")
    )
    assert(
      capture.messages.exists(
        _.startsWith("reviewer selection: no reviewer reported last round")
      ),
      capture.messages.mkString("\n")
    )

  test("narrowing never resurrects a reviewer the base selector excluded"):
    // `generic` is excluded by the base and never reported, so the only route
    // back into the round would be narrowing consulting something other than
    // `base`'s own result.
    val basePicksScalaFp =
      selector((all, _) => all.filter(_.name.value == "scala-fp"))
    val selectRound = ReviewerSelector
      .narrowingAcrossRounds(basePicksScalaFp)
      .prepare(all, Title("any"), List("notes.txt"))
    assertEquals(
      selectRound(List(reported(scalaFp))).map(_.name.value),
      List("scala-fp")
    )

  test("a base selector that picks nobody keeps picking nobody"):
    val capture = new SelectorSteps
    val picksNobody = selector((_, _) => Nil)
    val selectRound = ReviewerSelector
      .narrowingAcrossRounds(picksNobody)
      .prepare(all, Title("any"), List("notes.txt"))(using
        capture.ctx,
        summon[orca.InStage]
      )
    // The floor exists to stop NARROWING emptying the set, never to overrule a
    // selector that decided nobody should review this — so no fallback, and no
    // "re-running all 0 reviewer(s)" announcement either.
    assertEquals(selectRound(List(ReviewBatch(Nil))), Nil)
    assertEquals(capture.messages, Nil)
