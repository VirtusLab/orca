package orca.pr

import orca.tools.IssueHandle

import java.util.regex.Pattern

/** `body` ending with `Closes <owner>/<repo>#<n>`, so GitHub links the PR to
  * `issue` and closes it on merge. A `Closes` line for the same issue already
  * in `body` — e.g. one the summariser wrote, as `#<n>` or in full — is
  * removed, so the PR carries exactly one.
  */
def withClosingRef(body: String, issue: IssueHandle): String =
  val repo = Pattern.quote(s"${issue.owner}/${issue.repo}")
  val sameIssue = s"(?i)\\s*closes\\s+(?:$repo)?#${issue.number}\\.?\\s*".r
  val kept = body.linesIterator.filterNot(sameIssue.matches).mkString("\n")
  s"${kept.stripTrailing}\n\nCloses ${issue.shortRef}."
