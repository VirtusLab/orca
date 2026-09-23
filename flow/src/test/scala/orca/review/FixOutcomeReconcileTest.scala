package orca.review

import orca.plan.Title

/** [[FixOutcome.reconcile]]'s resolution rules, exercised directly: the loops
  * only ever see the reconciled result, so a rule that stops holding here is
  * invisible until an agent's reply happens to trip it.
  */
class FixOutcomeReconcileTest extends munit.FunSuite:

  private def handed(titles: String*): List[IdentifiedFinding] =
    IdentifiedFinding.identify(
      round = 1,
      open = Nil,
      keyed = KeyedFinding.forAgent(0, titles.toList.map(t => finding(t)))
    )

  /** The id [[handed]] gives the `n`-th (1-based) finding. */
  private def idOf(n: Int): FindingId = FindingId(s"R1.I1.$n")

  test("a key claims only its own finding, never one whose key it prefixes"):
    // `I1.1` prefixes `I1.10`; the echo names the tenth finding, so the first
    // must stay unaccounted.
    val findings = handed((1 to 10).map(n => s"finding $n")*)
    val reconciled = FixOutcome.reconcile(
      findings,
      FixOutcome(List(Title("I1.10 finding 10")), Nil)
    )
    assertEquals(reconciled.fixed, List(idOf(10)))

  test("a key followed by a letter does not claim that finding"):
    // The boundary check covers a following letter, not just a digit.
    val reconciled = FixOutcome.reconcile(
      handed("first", "second"),
      FixOutcome(Nil, List(DeclinedFinding(Title("I1.2C bus timing"), "no")))
    )
    assertEquals(reconciled.declined, Nil)
    assertEquals(reconciled.unresolvedEchoes, List("I1.2C bus timing"))

  test("a title differing only in case and spacing still resolves"):
    val reconciled = FixOutcome.reconcile(
      handed("Leaks a handle"),
      FixOutcome(
        Nil,
        List(DeclinedFinding(Title("  leaks   a handle "), "by design"))
      )
    )
    assertEquals(
      reconciled.declined,
      List(
        OpenFinding(
          idOf(1),
          Title("Leaks a handle"),
          OpenReason.Declined("by design"),
          None
        )
      )
    )

  test("a keyed echo with the which-alternative suffix resolves by key"):
    val reconciled = FixOutcome.reconcile(
      handed("leaks a handle"),
      FixOutcome(
        List(Title("I1.1 leaks a handle — closed it in a finally block")),
        Nil
      )
    )
    assertEquals(reconciled.fixed, List(idOf(1)))

  test("a keyless echo with the which-alternative suffix resolves by title"):
    val reconciled = FixOutcome.reconcile(
      handed("leaks a handle"),
      FixOutcome(
        List(Title("leaks a handle — closed it in a finally block")),
        Nil
      )
    )
    assertEquals(reconciled.fixed, List(idOf(1)))

  test("a keyless suffixed echo extending two titles stays unresolved"):
    // Both handed titles are proper prefixes of the echo, so picking either
    // would be a guess; the echo is dropped instead.
    val reconciled = FixOutcome.reconcile(
      handed("leaks a handle", "leaks a handle badly"),
      FixOutcome(List(Title("leaks a handle badly — fixed")), Nil)
    )
    assertEquals(reconciled.fixed, Nil)
    assertEquals(
      reconciled.unresolvedEchoes,
      List("leaks a handle badly — fixed")
    )

  test("a finding the fixer claimed twice is one fixed entry"):
    val reconciled = FixOutcome.reconcile(
      handed("real bug"),
      FixOutcome(List(Title("I1.1 real bug"), Title("real bug")), Nil)
    )
    assertEquals(reconciled.fixed, List(idOf(1)))

  test("a finding echoed in both lists counts as fixed only"):
    val reconciled = FixOutcome.reconcile(
      handed("real bug"),
      FixOutcome(
        List(Title("real bug")),
        List(DeclinedFinding(Title("real bug"), "on second thoughts"))
      )
    )
    assertEquals(reconciled.fixed, List(idOf(1)))
    assertEquals(reconciled.declined, Nil)
    assertEquals(reconciled.unaccounted, Nil)

  test("one finding reported twice in a round yields one entry"):
    // The two copies share an id, and buckets are keyed by id, so the second
    // copy is not dropped from `declined` only to reappear as unaccounted.
    val reconciled = FixOutcome.reconcile(
      handed("duplicate", "duplicate"),
      FixOutcome(Nil, List(DeclinedFinding(Title("duplicate"), "known")))
    )
    assertEquals(
      reconciled.declined,
      List(
        OpenFinding(
          idOf(1),
          Title("duplicate"),
          OpenReason.Declined("known"),
          None
        )
      )
    )
    assertEquals(reconciled.unaccounted, Nil)

  test("an echo matching nothing is dropped and its finding left unaccounted"):
    val reconciled = FixOutcome.reconcile(
      handed("real bug"),
      FixOutcome(Nil, List(DeclinedFinding(Title("something else"), "no")))
    )
    assertEquals(
      reconciled.unaccounted.map(_.finding),
      List(finding("real bug"))
    )
    assertEquals(reconciled.unresolvedEchoes, List("something else"))

  test("a title echo names every handed finding with that title"):
    // Two findings share a title but not a place, so they have two ids; an
    // echo naming only the title cannot tell them apart.
    val twoPlaces = IdentifiedFinding.identify(
      round = 1,
      open = Nil,
      keyed = KeyedFinding.forAgent(
        0,
        List("A.scala", "B.scala").map(file =>
          finding("unused import").copy(location = Some(Location(file, None)))
        )
      )
    )
    val reconciled = FixOutcome.reconcile(
      twoPlaces,
      FixOutcome(Nil, List(DeclinedFinding(Title("unused import"), "kept")))
    )
    assertEquals(reconciled.declined.map(_.id), List(idOf(1), idOf(2)))
