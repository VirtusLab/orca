package orca.review

import orca.util.{TextUtil, TextWrap}

// Rendering of review outcomes into `Step`-body text for the event log.

/** Wrap width shared by every renderer here, so one run's output lines up. */
private val WrapWidth: Int = 74

/** Format a single finding as the body lines of a `Step`.
  *
  * Shape: `- I1.1 title ...wrapped...`, optionally followed by ` at file:line`
  * and a ` suggestion: …` line. The leading `- ` makes the finding a bullet
  * within a multi-finding body; outer indentation is added by the caller
  * (typically [[formatReviewerOutcome]]).
  *
  * `key` is the [[FixRequest]] key the fixer is asked to echo for this finding,
  * shown so its "Fix I1.1/I1.2" narration names findings the reader can see.
  *
  * `description` is deliberately not rendered: the screen shows the short form,
  * and the fixer gets the long one from [[FixRequest]]'s own rendering.
  */
private[review] def formatFinding(key: String, finding: ReviewFinding): String =
  val header = TextWrap.wrap(
    s"- $key ${finding.title}",
    maxWidth = WrapWidth,
    continuation = "  "
  )
  val suggestion = finding.suggestion.map: s =>
    TextWrap.wrap(
      s"    suggestion: $s",
      maxWidth = WrapWidth,
      continuation = "      "
    )
  List(Some(header), locationLine(finding.location), suggestion).flatten
    .mkString("\n")

/** Where a finding points, as one indented line — shared by the display and the
  * fix prompt so a reader of either sees the same shape.
  */
private[review] def locationLine(location: Option[Location]): Option[String] =
  location.map(l => s"    at ${l.text}")

/** Format a reviewer's outcome as a `▶`-step body — heading line names the
  * reviewer + finding count, then bulleted finding details indented under it.
  * Clean reviews collapse to a single "<name>: 0 findings" line.
  *
  * `findings` already carry the keys the fix turn will use
  * ([[KeyedFinding.forAgent]]).
  */
private[review] def formatReviewerOutcome(
    reviewerName: String,
    findings: List[KeyedFinding]
): String =
  if findings.isEmpty then s"$reviewerName: 0 findings"
  else
    val header =
      s"$reviewerName: ${TextUtil.pluralize(findings.size, "finding")}"
    val bullets =
      findings.map(k => formatFinding(k.key, k.finding)).mkString("\n")
    s"$header\n$bullets"

/** The block a review loop prints when it stops with findings still open: one
  * `- title` line per finding, then where it points ([[locationLine]]) and the
  * reason it is still open. `None` when nothing is open.
  *
  * No [[FixRequest]] keys here: a key numbers one round's fix list, and this
  * block spans rounds — an entry from round one carries no key the last round
  * minted.
  */
private[review] def formatOpenFindings(
    open: List[OpenFinding]
): Option[String] =
  Option.when(open.nonEmpty):
    val lines = open.flatMap: f =>
      val reason = f.reasonLine
      val bullet = TextWrap.wrap(
        s"  - ${f.titleLine}",
        maxWidth = WrapWidth,
        continuation = "    "
      )
      val why = Option.when(reason.nonEmpty)(
        TextWrap.wrap(
          s"    $reason",
          maxWidth = WrapWidth,
          continuation = "    "
        )
      )
      List(Some(bullet), locationLine(f.location), why).flatten
    (s"Findings still open (${open.size}):" :: lines).mkString("\n")
