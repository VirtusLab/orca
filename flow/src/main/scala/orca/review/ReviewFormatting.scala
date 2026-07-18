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
  val location = (issue.file, issue.line) match
    case (Some(f), Some(l)) => Some(s"    at $f:$l")
    case (Some(f), None)    => Some(s"    at $f")
    case _                  => None
  val suggestion = issue.suggestion.map: s =>
    TextWrap.wrap(s"    suggestion: $s", maxWidth = 74, continuation = "      ")
  List(Some(header), location, suggestion).flatten.mkString("\n")

/** Format a reviewer's outcome as a `▶`-step body — heading line names the
  * reviewer + issue count, then bulleted issue details indented under it. Clean
  * reviews collapse to a single "<name>: 0 issues" line.
  */
private[review] def formatReviewerOutcome(
    reviewerName: String,
    result: ReviewResult
): String =
  if result.issues.isEmpty then s"$reviewerName: 0 issues"
  else
    val header =
      s"$reviewerName: ${TextUtil.pluralize(result.issues.size, "issue")}"
    val bullets = result.issues.map(formatIssue).mkString("\n")
    s"$header\n$bullets"
