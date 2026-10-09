// Review a PR, a branch, or local changes — a list of findings, no fixes.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.2.0"
//> using jvm 21

/** Prints review findings and changes nothing. For a PR target, also posts
  * them as a PR comment (needs `gh`); a re-run replaces that comment.
  *
  * The prompt says what to review: a PR reference or URL, a branch, a commit
  * range, "the uncommitted changes", or a piped-in diff.
  *
  * Reviewers are read-only, so a first stage writes the diff to a file for
  * them.
  *
  * ```bash
  * scala-cli run --workspace "$(mktemp -d)" review.sc -- "acme/widgets#42"
  * git diff | orca run review.sc
  * ```
  */

import orca.{*, given}

/** Where the resolver leaves the diff. Fixed, so a resumed run finds it. */
val DiffPath: String = ".orca/review.diff"

/** `prRef` is set only for a GitHub PR, and decides whether the report is
  * posted.
  */
case class Resolved(
    summary: String,
    changedFiles: List[String],
    prRef: Option[String]
) derives JsonData

flow(OrcaArgs(args)):
  val resolved = stage("Resolve what to review"):
    resolveTarget()

  if resolved.changedFiles.isEmpty then
    fail(s"No changed files found for: ${resolved.summary}")

  display(
    s"Reviewing ${resolved.summary} — ${resolved.changedFiles.size} file(s)"
  )

  val report = stage("Review"):
    reviewOnce(
      allReviewers(reviewAgent),
      Task(Title(resolved.summary), ""),
      ReviewDiff.InFile(DiffPath, resolved.changedFiles),
      // With a piped diff the run prompt is the diff; this keeps it out of
      // every reviewer prompt.
      userRequest = Some("")
    )

  display(report.render)

  resolved.prRef.foreach: ref =>
    stage("Post report on the PR"):
      gh.prHandle(ref) match
        case Right(pr) =>
          gh.upsertComment(
            pr,
            orcaCommentMarker(userPrompt, "review"),
            report.render
          )
        case Left(why) =>
          fail(s"cannot post the report on $ref: $why — post the report " +
            "above on the PR yourself")

  // Not removed on failure: a resumed run skips the resolver and re-reads it.
  os.remove.all(os.pwd / os.RelPath(DiffPath))

/** Writes the prompt's diff to [[DiffPath]]. Written to disk, not returned, so
  * the diff costs no output tokens.
  */
def resolveTarget()(using FlowContext, InStage): Resolved =
  reviewAgent.cheap
    .resultAs[Resolved]
    .autonomous
    .run(
      s"""Work out what change the following request refers to, and write its
         |complete unified diff to `$DiffPath`.
         |
         |Request:
         |$userPrompt
         |
         |The request may name a GitHub PR (a `<owner>/<repo>#<number>` ref or
         |a URL), a branch, a commit or commit range, the uncommitted local
         |changes — or it may BE the diff itself, pasted or piped in. Pick
         |whichever reading fits; when in doubt prefer the local working tree.
         |
         |Write the diff with a shell redirect (`git diff … > $DiffPath`, `gh
         |pr diff … > $DiffPath`, or a heredoc when the request already carries
         |the diff). Do NOT reproduce the diff in your answer.
         |
         |Then report: `summary`, a one-line summary of what is under review
         |(e.g. "PR acme/widgets#42: add pagination"); `changedFiles`, the
         |repo-relative paths of the changed files; and `prRef`, only when the
         |target is a GitHub PR, its `<owner>/<repo>#<number>` ref.""".stripMargin
    )
