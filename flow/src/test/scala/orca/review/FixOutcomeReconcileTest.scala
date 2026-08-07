package orca.review

import orca.plan.Title

/** [[FixOutcome.reconcile]]'s resolution rules, exercised directly: the loops
  * only ever see the reconciled result, so a rule that stops holding here is
  * invisible until an agent's reply happens to trip it.
  */
class FixOutcomeReconcileTest extends munit.FunSuite:

  private def issue(title: String): ReviewIssue =
    ReviewIssue(
      severity = Severity.Warning,
      confidence = Confidence.orThrow(0.9),
      title = Title(title),
      description = title,
      location = None,
      suggestion = None
    )

  private def handed(titles: String*): List[ReviewIssue] =
    titles.toList.map(issue)

  test("a key claims only its own issue, never one whose key it prefixes"):
    // `I1` prefixes `I10`; the echo names the tenth issue, so the first must
    // stay unaccounted.
    val issues = handed((1 to 10).map(n => s"finding $n")*)
    val reconciled = FixOutcome.reconcile(
      issues,
      FixOutcome(List(Title("I10 finding 10")), Nil)
    )
    assertEquals(reconciled.fixed, List(Title("finding 10")))

  test("a key followed by a letter does not claim that issue"):
    // "I2C bus timing" opens with `I2` but names something else entirely.
    val reconciled = FixOutcome.reconcile(
      handed("first", "second"),
      FixOutcome(Nil, List(IgnoredIssue(Title("I2C bus timing"), "no")))
    )
    assertEquals(reconciled.ignored, Nil)
    assertEquals(reconciled.unresolvedEchoes, List("I2C bus timing"))

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

  test("an issue the fixer claimed twice is one fixed entry"):
    val reconciled = FixOutcome.reconcile(
      handed("real bug"),
      FixOutcome(List(Title("I1 real bug"), Title("real bug")), Nil)
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
