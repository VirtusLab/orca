package orca.review

/** The minimum confidence a [[ReviewIssue]] must carry to reach the fixer, per
  * [[Severity]]. Findings below their bar are not fixed, but they are not lost
  * either: `reviewAndFixLoop` records its rejects in the returned
  * [[IgnoredIssues]] with [[rejectionReason]].
  *
  * Severity and confidence are orthogonal: how bad the finding is if real, and
  * how sure the reviewer is that it is real. A single flat bar conflates them —
  * a Warning at 0.55 would be dropped on the same terms as an Info at 0.55,
  * even though acting on the Warning is worth the risk of a false positive and
  * acting on the Info is mostly noise. A per-severity bar prices that trade-off
  * where it belongs: a tentative Critical is still worth a look, while an
  * uncertain Info is the noisiest kind of finding and is held to the strictest
  * bar.
  */
case class ConfidenceGate(
    critical: Confidence,
    warning: Confidence,
    info: Confidence
):
  /** The bar `severity` must clear. */
  def minimumFor(severity: Severity): Confidence = severity match
    case Severity.Critical => critical
    case Severity.Warning  => warning
    case Severity.Info     => info

  /** Whether `issue` clears the bar for its severity. */
  def admits(issue: ReviewIssue): Boolean =
    issue.confidence.clears(minimumFor(issue.severity))

  /** Why `issue` was held back, naming the bar it missed — the reason recorded
    * against a gated-out finding.
    */
  def rejectionReason(issue: ReviewIssue): String =
    s"below the ${issue.severity} confidence gate " +
      s"(${issue.confidence.value} < ${minimumFor(issue.severity).value})"

object ConfidenceGate:
  /** The numbers are judgment calls, not measurements — callers who find them
    * mis-tuned for their reviewers can pass their own gate, or adjust one bar
    * with `ConfidenceGate.default.copy(info = Confidence.orThrow(0.7))`.
    */
  val default: ConfidenceGate =
    ConfidenceGate(
      critical = Confidence.orThrow(0.5),
      warning = Confidence.orThrow(0.6),
      info = Confidence.orThrow(0.8)
    )
