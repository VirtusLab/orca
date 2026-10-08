package orca.plan

class TriageReplyTest extends munit.FunSuite:
  import TriageReply.Kind.{Reject, TestableBug, UntestableBug, Change}

  private val blank = TriageReply(
    kind = Reject,
    reply = "",
    summary = "",
    brief = "",
    failingTestPath = "",
    reproductionSteps = ""
  )

  test("Reject requires a reply"):
    assertEquals(
      blank.copy(reply = "duplicate of #4").toTriage,
      Right(Triage.Reject("duplicate of #4"))
    )
    assert(blank.copy(reply = "  ").toTriage.isLeft)

  test("TestableBug requires summary, brief and failingTestPath"):
    val ok = blank.copy(
      kind = TestableBug,
      summary = "Foo overflows",
      brief = "see Foo.scala",
      failingTestPath = "src/test/FooTest.scala"
    )
    assertEquals(
      ok.toTriage,
      Right(
        Triage.Accept(
          summary = "Foo overflows",
          brief = "see Foo.scala",
          kind = Triage.Kind.TestableBug("src/test/FooTest.scala")
        )
      )
    )
    assert(ok.copy(failingTestPath = " ").toTriage.isLeft)
    assert(ok.copy(summary = " ").toTriage.isLeft)
    assert(ok.copy(brief = " ").toTriage.isLeft)

  test("UntestableBug requires reproductionSteps"):
    val ok = blank.copy(
      kind = UntestableBug,
      summary = "UI freezes",
      brief = "b",
      reproductionSteps = "1. open 2. click"
    )
    assertEquals(
      ok.toTriage,
      Right(
        Triage.Accept(
          summary = "UI freezes",
          brief = "b",
          kind = Triage.Kind.UntestableBug("1. open 2. click")
        )
      )
    )
    assert(ok.copy(reproductionSteps = " ").toTriage.isLeft)

  test("Change needs only summary and brief"):
    assertEquals(
      blank.copy(kind = Change, summary = "Add X", brief = "b").toTriage,
      Right(
        Triage.Accept(summary = "Add X", brief = "b", kind = Triage.Kind.Change)
      )
    )

  test("Announce[TriageReply] defers to Triage's message"):
    val change = blank.copy(kind = Change, summary = "Add X", brief = "b")
    assertEquals(
      summon[orca.agents.Announce[TriageReply]].message(change),
      Some("Triage: change — Add X")
    )

  test("Announce[TriageReply] is None for a malformed payload"):
    assertEquals(summon[orca.agents.Announce[TriageReply]].message(blank), None)
