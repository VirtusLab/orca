package orca.review

import orca.util.{TextUtil, TextWrap}

// Rendering of review outcomes into `Step`-body text for the event log.

/** Format a single review comment as the body lines of a `Step`.
  *
  * Shape: `- [Severity] title ...wrapped...`, optionally followed by ` at
  * file:line` and a ` suggestion: …` line. The leading `- ` makes the issue a
  * bullet within a multi-issue body; outer indentation is added by the caller
  * (typically [[formatReviewerOutcome]]).
  *
  * `description` is deliberately not rendered — it's the longer form fed back
  * to the fixing agent; the user sees the short form on screen.
  */
private[review] def formatIssue(issue: ReviewIssue): String =
  val header = TextWrap.wrap(
    s"- [${issue.severity}] ${issue.title}",
    maxWidth = 74,
    continuation = "  "
  )
  val location = issue.location.map:
    case Location(f, Some(l)) => s"    at $f:$l"
    case Location(f, None)    => s"    at $f"
  val suggestion = issue.suggestion.map: s =>
    TextWrap.wrap(s"    suggestion: $s", maxWidth = 74, continuation = "      ")
  List(Some(header), location, suggestion).flatten.mkString("\n")

/** Format a reviewer's outcome as a `▶`-step body — heading line names the
  * reviewer + issue count, then bulleted issue details indented under it. Clean
  * reviews collapse to a single "<name>: 0 issues" line.
  *
  * `result` holds only what cleared the confidence gate; `droppedCount` is how
  * many of that reviewer's findings it held back, noted in the heading so a
  * quiet reviewer isn't confused with a gated-out one. Those findings are
  * recorded by name in the loop's `IgnoredIssues`, not here.
  */
private[review] def formatReviewerOutcome(
    reviewerName: String,
    result: ReviewResult,
    droppedCount: Int
): String =
  val gated =
    if droppedCount == 0 then ""
    else s" ($droppedCount below the confidence gate)"
  if result.issues.isEmpty then s"$reviewerName: 0 issues$gated"
  else
    val header =
      s"$reviewerName: ${TextUtil.pluralize(result.issues.size, "issue")}$gated"
    val bullets = result.issues.map(formatIssue).mkString("\n")
    s"$header\n$bullets"
