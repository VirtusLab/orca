package orca.review

import orca.plan.Title

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
  /** `prior` given ids of this loop's own, one per defect: each loop mints ids
    * from round one, so what two loops left open can share an id, and a defect
    * two loops left open — same title and location — is one entry, with the
    * latest reason.
    */
  def withSeedIds(prior: List[OpenFinding]): List[OpenFinding] =
    def defectOf(f: OpenFinding) = defect(f.title, f.location)
    val latest = prior.map(f => defectOf(f) -> f).toMap
    prior
      .map(defectOf)
      .distinct
      .zipWithIndex
      .map((d, i) => latest(d).copy(id = FindingId.seed(i + 1)))

  /** Give each of one round's findings its id.
    *
    * A finding takes the id of the open entry it reopens: the one its `reopens`
    * names, or else the sole open entry with the same title in the same file,
    * for a reviewer that re-reports without naming the id — unless findings of
    * different defects in the round all claim that entry; then none takes it by
    * title. Findings with the same title and location are one defect and share
    * an id: the entry any of them reopens, or else a new one.
    */
  def identify(
      round: Int,
      open: List[OpenFinding],
      keyed: List[KeyedFinding]
  ): List[IdentifiedFinding] =
    val idOfDefect = keyed
      .map(k => k -> reopened(open, keyed.map(_.finding), k.finding))
      .groupBy((k, _) => defectOf(k.finding))
      .view
      .mapValues: copies =>
        // A new id is minted from the first copy's key.
        copies
          .collectFirst { case (_, Some(id)) => id }
          .getOrElse(FindingId.reported(round, copies.head._1.key))
      .toMap
    keyed.map(k => IdentifiedFinding(idOfDefect(defectOf(k.finding)), k))

  /** The open entry `finding` reopens, given all of `roundFindings`. */
  private def reopened(
      open: List[OpenFinding],
      roundFindings: List[ReviewFinding],
      finding: ReviewFinding
  ): Option[FindingId] =
    def named(f: ReviewFinding): Option[FindingId] =
      f.reopens.filter(id => open.exists(_.id == id))
    def uncontested(id: FindingId): Boolean =
      roundFindings
        .filter(f =>
          named(f).contains(id) ||
            soleEntryWithTitleInFile(open, f).contains(id)
        )
        .map(defectOf)
        .distinct
        .size == 1
    named(finding).orElse(
      soleEntryWithTitleInFile(open, finding).filter(uncontested)
    )

  private def soleEntryWithTitleInFile(
      open: List[OpenFinding],
      finding: ReviewFinding
  ): Option[FindingId] =
    open.filter(o =>
      normalisedTitle(o.title.value) == normalisedTitle(finding.title.value) &&
        o.location.map(_.file) == finding.location.map(_.file)
    ) match
      case List(only) => Some(only.id)
      case _          => None

  private def defectOf(f: ReviewFinding): (String, Option[Location]) =
    defect(f.title, f.location)

  /** What makes two findings one defect. */
  private def defect(
      title: Title,
      location: Option[Location]
  ): (String, Option[Location]) =
    (normalisedTitle(title.value), location)
