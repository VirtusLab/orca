package orca.pr

import orca.tools.IssueHandle

import java.util.regex.Pattern
import scala.util.matching.Regex

/** `body` ending with `Closes <owner>/<repo>#<n>`, so GitHub links the PR to
  * `issue` and closes it on merge. A line in `body` that starts with a closing
  * keyword (`Closes`, `Fixes`, `Resolves`, …) and names the same issue — e.g.
  * one the summariser wrote — is removed, so the PR carries exactly one.
  */
def withClosingRef(body: String, issue: IssueHandle): String =
  val closesSameIssue = closingLinePattern(issue)
  val kept = body.linesIterator
    .filterNot(closesSameIssue.findFirstIn(_).isDefined)
    .mkString("\n")
  s"${kept.stripTrailing}\n\nCloses ${issue.shortRef}."

/** Matches a line opening with a closing keyword, optionally as a list item,
  * that references `issue` as `#n`, `owner/repo#n` or its issue URL.
  */
private def closingLinePattern(issue: IssueHandle): Regex =
  val repo = Pattern.quote(s"${issue.owner}/${issue.repo}")
  val n = issue.number
  // A bare `#n` must not be the tail of another repo's `other/repo#n`.
  val ref =
    s"(?:(?<![\\w./-])|$repo)#$n(?!\\d)|github\\.com/$repo/issues/$n(?!\\d)"
  val keyword = "(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)"
  s"(?i)^\\s*(?:[-*]\\s+)?$keyword:?\\s.*(?:$ref)".r
