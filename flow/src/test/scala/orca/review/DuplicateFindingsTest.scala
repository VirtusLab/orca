package orca.review

import orca.plan.Title

class DuplicateFindingsTest extends munit.FunSuite:

  private def at(
      title: String,
      file: String,
      line: Option[Int]
  ): ReviewIssue =
    ReviewIssue(
      title = Title(title),
      description = s"why $title matters",
      location = Some(Location(file, line)),
      suggestion = None
    )

  /** One reviewer's round: its findings keyed as the fixer will see them. */
  private def from(reviewer: String, agentIndex: Int)(
      issues: ReviewIssue*
  ): List[ReportedIssue] =
    KeyedIssue
      .forAgent(agentIndex, issues.toList)
      .map(ReportedIssue(reviewer, _))

  test("findings at the same file and line become the first reporter's entry"):
    // The surviving key and title are what the reader saw on screen under that
    // reviewer's name, and what the fixer is asked to echo back.
    val merged = DuplicateFindings.merge(
      from("security", 0)(at("unchecked input", "A.scala", Some(7))) ++
        from("scala-fp", 1)(at("validate the input", "A.scala", Some(7)))
    )
    assertEquals(
      merged.map(k => (k.key, k.issue.title)),
      List(("I1.1", Title("unchecked input")))
    )

  test("the surviving entry names the reviewers it absorbed"):
    val merged = DuplicateFindings.merge(
      from("security", 0)(at("unchecked input", "A.scala", Some(7))) ++
        from("scala-fp", 1)(at("validate the input", "A.scala", Some(7))) ++
        from("simplicity", 2)(at("guard the input", "A.scala", Some(7)))
    )
    assertEquals(
      merged.map(_.issue.description),
      List(
        "why unchecked input matters\n\nAlso reported by scala-fp, simplicity."
      )
    )

  test("the same file at different lines is not a duplicate"):
    val merged = DuplicateFindings.merge(
      from("security", 0)(at("one", "A.scala", Some(7))) ++
        from("scala-fp", 1)(at("two", "A.scala", Some(8)))
    )
    assertEquals(merged.map(_.issue.title), List(Title("one"), Title("two")))

  test("two file-scope findings in one file are not a duplicate"):
    // `Location(file, None)` is the whole file, so two reviewers naming it are
    // usually reporting two different things — folding them drops one.
    val merged = DuplicateFindings.merge(
      from("security", 0)(at("one", "A.scala", None)) ++
        from("scala-fp", 1)(at("two", "A.scala", None))
    )
    assertEquals(merged.map(_.issue.title), List(Title("one"), Title("two")))

  test("one reviewer's two findings on one line are not a duplicate"):
    // A reviewer does not report the same problem twice; and naming it as its
    // own co-reporter would read as a second reviewer confirming it.
    val merged = DuplicateFindings.merge(
      from("security", 0)(
        at("one", "A.scala", Some(7)),
        at("two", "A.scala", Some(7))
      )
    )
    assertEquals(merged.map(_.issue.title), List(Title("one"), Title("two")))
    assert(
      merged.forall(!_.issue.description.contains("Also reported by")),
      merged.map(_.issue.description)
    )

  test("findings without a location never merge"):
    // Nothing but wording to compare, and wrongly folding two drops one from
    // the run's record of what was found.
    val nowhere = (title: String) =>
      ReviewIssue(Title(title), title, location = None, suggestion = None)
    val merged = DuplicateFindings.merge(
      from("security", 0)(nowhere("one")) ++
        from("scala-fp", 1)(nowhere("two"))
    )
    assertEquals(merged.map(_.issue.title), List(Title("one"), Title("two")))

  test("a finding no other reviewer reported is untouched"):
    val only = from("security", 0)(at("unchecked input", "A.scala", Some(7)))
    assertEquals(DuplicateFindings.merge(only), only.map(_.keyed))
