package orca.review

/** A round's finding with the [[FindingId]] the loop tracks it under across
  * rounds, alongside the key its fix turn names it by.
  */
private[review] case class IdentifiedFinding(
    id: FindingId,
    keyed: KeyedFinding
):
  def finding: ReviewFinding = keyed.finding

  /** This finding recorded as still open, for `reason`. */
  def open(reason: OpenReason): OpenFinding =
    OpenFinding(id, finding.title, reason, finding.location)

private[review] object IdentifiedFinding:
  /** Give each of one round's findings its id.
    *
    * A finding takes the id of the open entry it reopens: the one its `reopens`
    * names, or else the sole open entry with the same title in the same file,
    * for a reviewer that re-reports without naming the id. Anything else is
    * new, and findings in the round with the same title and location share one
    * id, so the same defect from two reviewers is one entry.
    */
  def identify(
      round: Int,
      open: List[OpenFinding],
      keyed: List[KeyedFinding]
  ): List[IdentifiedFinding] =
    keyed.map: k =>
      val id = reopened(open, k.finding).getOrElse:
        // The first occurrence of a defect names the id every later one shares.
        val first =
          keyed.find(o => sameDefect(o.finding, k.finding)).getOrElse(k)
        FindingId.reported(round, first.key)
      IdentifiedFinding(id, k)

  private def reopened(
      open: List[OpenFinding],
      finding: ReviewFinding
  ): Option[FindingId] =
    finding.reopens
      .filter(id => open.exists(_.id == id))
      .orElse:
        open.filter(o =>
          normalisedTitle(o.title.value) == normalisedTitle(
            finding.title.value
          ) &&
            o.location.map(_.file) == finding.location.map(_.file)
        ) match
          case List(only) => Some(only.id)
          case _          => None

  private def sameDefect(a: ReviewFinding, b: ReviewFinding): Boolean =
    normalisedTitle(a.title.value) == normalisedTitle(b.title.value) &&
      a.location == b.location
