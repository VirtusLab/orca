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
  /** `prior` with ids of their own, for a loop to be seeded with: each loop
    * mints ids from round one, so what two loops left open can share one.
    */
  def seed(prior: List[OpenFinding]): List[OpenFinding] =
    prior.zipWithIndex.map((f, i) => f.copy(id = FindingId.seed(i + 1)))

  /** Give each of one round's findings its id.
    *
    * A finding takes the id of the open entry it reopens: the one its `reopens`
    * names, or else the sole open entry with the same title in the same file,
    * for a reviewer that re-reports without naming the id — unless different
    * findings in the round match that entry so, as then which one it is can't
    * be told. Findings with the same title and location are one defect and
    * share an id: the entry any of them reopens, or else a new one.
    */
  def identify(
      round: Int,
      open: List[OpenFinding],
      keyed: List[KeyedFinding]
  ): List[IdentifiedFinding] =
    val reopening = keyed.map(k => k -> reopened(open, keyed, k.finding))
    reopening.map: (k, _) =>
      val sameDefect =
        reopening.filter((other, _) =>
          defect(other.finding) == defect(k.finding)
        )
      val first = sameDefect.map(_._1).headOption.getOrElse(k)
      val id = sameDefect
        .collectFirst { case (_, Some(id)) => id }
        .getOrElse(FindingId.reported(round, first.key))
      IdentifiedFinding(id, k)

  /** The open entry `finding` reopens, among `round`'s findings. */
  private def reopened(
      open: List[OpenFinding],
      round: List[KeyedFinding],
      finding: ReviewFinding
  ): Option[FindingId] =
    def uncontested(id: FindingId): Boolean =
      round
        .map(_.finding)
        .filter(f => sameTitleInFile(open, f).contains(id))
        .map(defect)
        .distinct
        .size == 1
    finding.reopens
      .filter(id => open.exists(_.id == id))
      .orElse(sameTitleInFile(open, finding).filter(uncontested))

  /** The sole open entry with `finding`'s title in its file. */
  private def sameTitleInFile(
      open: List[OpenFinding],
      finding: ReviewFinding
  ): Option[FindingId] =
    open.filter(o =>
      normalisedTitle(o.title.value) == normalisedTitle(finding.title.value) &&
        o.location.map(_.file) == finding.location.map(_.file)
    ) match
      case List(only) => Some(only.id)
      case _          => None

  /** What makes two of a round's findings one defect. */
  private def defect(f: ReviewFinding): (String, Option[Location]) =
    (normalisedTitle(f.title.value), f.location)
