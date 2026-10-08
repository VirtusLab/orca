package orca.review

import orca.agents.JsonData

/** A change to review once: a one-line `summary`, the repo-relative path of a
  * file holding its unified diff, and the files it changes. The diff stays in a
  * file because read-only reviewers cannot produce it themselves, and pasting a
  * large diff into every reviewer's prompt costs each of them the whole diff.
  */
case class ReviewTarget(
    summary: String,
    diffPath: String,
    changedFiles: List[String]
) derives JsonData
