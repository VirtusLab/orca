package orca.review

import orca.plan.Title
import orca.util.{TextUtil, TextWrap}

// Rendering of review outcomes into `Step`-body text for the event log.

/** Wrap width shared by every renderer here, so one run's output lines up. */
private val WrapWidth: Int = 74

/** Format a single review comment as the body lines of a `Step`.
  *
  * Shape: `- I1.1 [Severity] title ...wrapped...`, optionally followed by ` at
  * file:line` and a ` suggestion: …` line. The leading `- ` makes the issue a
  * bullet within a multi-issue body; outer indentation is added by the caller
  * (typically [[formatReviewerOutcome]]).
  *
  * `key` is the [[FixRequest]] key the fixer is asked to echo for this issue,
  * shown so its "Fix I1.1/I1.2" narration names findings the reader can see.
  *
  * `description` is deliberately not rendered: the screen shows the short form,
  * and the fixer gets the long one from [[FixRequest]]'s own rendering.
  */
private[review] def formatIssue(key: String, issue: ReviewIssue): String =
  val header = TextWrap.wrap(
    s"- $key [${issue.severity}] ${issue.title}",
    maxWidth = WrapWidth,
    continuation = "  "
  )
  val suggestion = issue.suggestion.map: s =>
    TextWrap.wrap(
      s"    suggestion: $s",
      maxWidth = WrapWidth,
      continuation = "      "
    )
  List(Some(header), locationLine(issue.location), suggestion).flatten
    .mkString("\n")

/** Where a finding points, as one indented line — shared by the display and the
  * fix prompt so a reader of either sees the same shape.
  */
private[review] def locationLine(location: Option[Location]): Option[String] =
  location.map:
    case Location(f, Some(l)) => s"    at $f:$l"
    case Location(f, None)    => s"    at $f"

/** Format a reviewer's outcome as a `▶`-step body — heading line names the
  * reviewer + issue count, then bulleted issue details indented under it. Clean
  * reviews collapse to a single "<name>: 0 issues" line.
  *
  * `issues` already carry the keys the fix turn will use
  * ([[KeyedIssue.forAgent]]).
  */
private[review] def formatReviewerOutcome(
    reviewerName: String,
    issues: List[KeyedIssue]
): String =
  if issues.isEmpty then s"$reviewerName: 0 issues"
  else
    val header = s"$reviewerName: ${TextUtil.pluralize(issues.size, "issue")}"
    val bullets = issues.map(k => formatIssue(k.key, k.issue)).mkString("\n")
    s"$header\n$bullets"

/** The block a review loop prints when it stops with findings still open: one
  * `- [Severity] title` line per finding, with the reason it is still open
  * below it. `None` when nothing is open.
  *
  * No [[FixRequest]] keys here: a key numbers one round's fix list, and this
  * block spans rounds — an entry from round one carries no key the last round
  * minted.
  *
  * A title missing from `severities` renders unprefixed rather than guessing a
  * severity.
  */
private[review] def formatUnresolvedFindings(
    open: List[IgnoredIssue],
    severities: Map[Title, Severity]
): Option[String] =
  Option.when(open.nonEmpty):
    val lines = open.flatMap: i =>
      val label = severities
        .get(i.title)
        .fold(i.title.value)(s => s"[$s] ${i.title.value}")
      // The fixer writes the reason, so it can arrive with its own line breaks;
      // collapsing them keeps continuation lines under the bullet.
      val reason = i.reason.trim.replaceAll("\\s+", " ")
      TextWrap.wrap(s"  - $label", maxWidth = WrapWidth, continuation = "    ")
        :: Option
          .when(reason.nonEmpty)(
            TextWrap
              .wrap(s"    $reason", maxWidth = WrapWidth, continuation = "    ")
          )
          .toList
    (s"Unresolved findings (${open.size}):" :: lines).mkString("\n")
