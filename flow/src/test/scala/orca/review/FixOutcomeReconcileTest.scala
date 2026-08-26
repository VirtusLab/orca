package orca.review

import orca.plan.Title

/** [[FixOutcome.reconcile]]'s resolution rules, exercised directly: the loops
  * only ever see the reconciled result, so a rule that stops holding here is
  * invisible until an agent's reply happens to trip it.
  */
class FixOutcomeReconcileTest extends munit.FunSuite:

  private def handed(titles: String*): List[KeyedIssue] =
    KeyedIssue.forAgent(0, titles.toList.map(t => issue(t)))

  test("a key claims only its own issue, never one whose key it prefixes"):
    // `I1.1` prefixes `I1.10`; the echo names the tenth issue, so the first
    // must stay unaccounted.
    val issues = handed((1 to 10).map(n => s"finding $n")*)
    val reconciled = FixOutcome.reconcile(
      issues,
      FixOutcome(List(Title("I1.10 finding 10")), Nil)
    )
    assertEquals(reconciled.fixed, List(Title("finding 10")))

  test("a key followed by a letter does not claim that issue"):
    // The boundary check covers a following letter, not just a digit.
    val reconciled = FixOutcome.reconcile(
      handed("first", "second"),
      FixOutcome(Nil, List(IgnoredIssue(Title("I1.2C bus timing"), "no")))
    )
    assertEquals(reconciled.ignored, Nil)
    assertEquals(reconciled.unresolvedEchoes, List("I1.2C bus timing"))

  test("a title differing only in case and spacing still resolves"):
    val reconciled = FixOutcome.reconcile(
      handed("Leaks a handle"),
      FixOutcome(
        Nil,
        List(IgnoredIssue(Title("  leaks   a handle "), "by design"))
      )
    )
    assertEquals(
      reconciled.ignored,
      List(IgnoredIssue(Title("Leaks a handle"), "by design"))
    )

  test("a keyed echo with the which-alternative suffix resolves by key"):
    val reconciled = FixOutcome.reconcile(
      handed("leaks a handle"),
      FixOutcome(
        List(Title("I1.1 leaks a handle — closed it in a finally block")),
        Nil
      )
    )
    assertEquals(reconciled.fixed, List(Title("leaks a handle")))

  test("a keyless echo with the which-alternative suffix resolves by title"):
    val reconciled = FixOutcome.reconcile(
      handed("leaks a handle"),
      FixOutcome(
        List(Title("leaks a handle — closed it in a finally block")),
        Nil
      )
    )
    assertEquals(reconciled.fixed, List(Title("leaks a handle")))

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

  test("an issue the fixer claimed twice is one fixed entry"):
    val reconciled = FixOutcome.reconcile(
      handed("real bug"),
      FixOutcome(List(Title("I1.1 real bug"), Title("real bug")), Nil)
    )
    assertEquals(reconciled.fixed, List(Title("real bug")))

  test("an issue echoed in both lists counts as fixed only"):
    val reconciled = FixOutcome.reconcile(
      handed("real bug"),
      FixOutcome(
        List(Title("real bug")),
        List(IgnoredIssue(Title("real bug"), "on second thoughts"))
      )
    )
    assertEquals(reconciled.fixed, List(Title("real bug")))
    assertEquals(reconciled.ignored, Nil)
    assertEquals(reconciled.unaccounted, Nil)

  test("one title reported by two reviewers yields one entry"):
    // Buckets are keyed by title throughout, so the second copy is not dropped
    // from `ignored` only to reappear as unaccounted.
    val reconciled = FixOutcome.reconcile(
      handed("duplicate", "duplicate"),
      FixOutcome(Nil, List(IgnoredIssue(Title("duplicate"), "known")))
    )
    assertEquals(
      reconciled.ignored,
      List(IgnoredIssue(Title("duplicate"), "known"))
    )
    assertEquals(reconciled.unaccounted, Nil)

  test("an echo matching nothing is dropped and its issue left unaccounted"):
    val reconciled = FixOutcome.reconcile(
      handed("real bug"),
      FixOutcome(Nil, List(IgnoredIssue(Title("something else"), "no")))
    )
    assertEquals(reconciled.unaccounted, List(Title("real bug")))
    assertEquals(reconciled.unresolvedEchoes, List("something else"))
