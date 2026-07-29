package orca.review

/** The minimum confidence a [[ReviewIssue]] must carry to reach the fixer, per
  * [[Severity]].
  *
  * Severity and confidence are orthogonal: how bad the finding is if real, and
  * how sure the reviewer is that it is real. A single flat bar conflates them —
  * a Warning at 0.55 would be dropped on the same terms as an Info at 0.55,
  * even though acting on the Warning is worth the risk of a false positive and
  * acting on the Info is mostly noise. A per-severity bar prices that trade-off
  * where it belongs.
  */
case class ConfidenceGate(critical: Double, warning: Double, info: Double):
  /** Whether `issue` clears the bar for its severity. */
  def admits(issue: ReviewIssue): Boolean =
    val minimum = issue.severity match
      case Severity.Critical => critical
      case Severity.Warning  => warning
      case Severity.Info     => info
    issue.confidence >= minimum

object ConfidenceGate:
  /** A tentative Critical is still worth a look; a Warning sits low enough that
    * a source-verified one the reviewer hedges on survives; an uncertain Info
    * is the noisiest kind of finding, so it is held to the strictest bar.
    *
    * The numbers are judgment calls, not measurements — callers who find them
    * mis-tuned for their reviewers can pass their own gate, or adjust one bar
    * with `ConfidenceGate.default.copy(info = ...)`.
    */
  val default: ConfidenceGate =
    ConfidenceGate(critical = 0.5, warning = 0.6, info = 0.8)
