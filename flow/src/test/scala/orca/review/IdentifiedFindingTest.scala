package orca.review

import orca.plan.Title

class IdentifiedFindingTest extends munit.FunSuite:

  private def open(id: String, title: String, file: String): OpenFinding =
    OpenFinding(
      FindingId(id),
      Title(title),
      OpenReason.NoFixes,
      Some(Location(file, None))
    )

  private def reported(
      title: String,
      file: String,
      reopens: Option[String] = None
  ): ReviewFinding =
    finding(title).copy(
      location = Some(Location(file, None)),
      reopens = reopens.map(FindingId(_))
    )

  private def idsOf(
      open: List[OpenFinding],
      findings: ReviewFinding*
  ): List[FindingId] =
    IdentifiedFinding
      .identify(
        round = 2,
        open = open,
        keyed = KeyedFinding.forAgent(0, findings.toList)
      )
      .map(_.id)

  test("a finding naming an open id takes it, whatever its title"):
    assertEquals(
      idsOf(
        List(open("R1.I1.1", "leaks a handle", "A.scala")),
        reported("stream left open", "B.scala", reopens = Some("R1.I1.1"))
      ),
      List(FindingId("R1.I1.1"))
    )

  test("a finding naming an id that is not open falls back to its title"):
    assertEquals(
      idsOf(
        List(open("R1.I1.1", "leaks a handle", "A.scala")),
        reported("Leaks  a handle", "A.scala", reopens = Some("R9.I9.9"))
      ),
      List(FindingId("R1.I1.1"))
    )

  test("the same title in another file is a new finding"):
    assertEquals(
      idsOf(
        List(open("R1.I1.1", "missing test", "A.scala")),
        reported("missing test", "B.scala")
      ),
      List(FindingId("R2.I1.1"))
    )

  test("one round's findings sharing title and location share an id"):
    assertEquals(
      idsOf(
        Nil,
        reported("missing test", "A.scala"),
        reported("missing test", "B.scala"),
        reported("missing test", "A.scala")
      ),
      List(FindingId("R2.I1.1"), FindingId("R2.I1.2"), FindingId("R2.I1.1"))
    )
