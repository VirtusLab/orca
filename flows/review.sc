// Review a PR, a branch, or local changes — a list of findings, no fixes.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.10"
//> using jvm 21

/** Review-only flow: no planning, no coding, nothing committed.
  *
  * The prompt says what to review, in whatever form suits: a PR reference or
  * URL, a branch name, "the uncommitted changes", a commit range, or a diff
  * piped straight in (`git diff | orca run review.sc`). A resolver stage works
  * out what that refers to and materialises the diff once; from there the flow:
  *
  *   1. Has a cheap-tier agent pick reviewers, after each reviewer's `files:`
  *      filter.
  *   1. Runs the picked reviewers concurrently, each returning a structured
  *      `ReviewResult`.
  *   1. Prints every finding, in reviewer-completion order.
  *   1. Posts the same report on the PR when the target was one — through
  *      `upsertComment`, so a re-run replaces its previous report rather than
  *      stacking a second one.
  *
  * The reviewers are read-only — no shell, so they cannot fetch a PR or run
  * `git diff` themselves. Hence the resolver stage: it has full tools, writes
  * the unified diff to a file, and returns only metadata; each reviewer then
  * reads that file and explores the repo around it.
  *
  * Nothing here fixes anything — for review-then-fix, use `implement.sc` or
  * `quick.sc`.
  *
  * ```bash
  * scala-cli run --workspace "$(mktemp -d)" review.sc -- "acme/widgets#42"
  * scala-cli run --workspace "$(mktemp -d)" review.sc -- "the uncommitted changes"
  * git diff | orca run review.sc
  * ```
  *
  * Requires the configured role agents logged in (`claude` by default), and
  * `gh` authenticated when the target is a PR.
  */

import orca.{*, given}

/** Where the resolver leaves the diff. Fixed rather than per-prompt so a resume
  * finds the same file; removed once the report is out.
  */
val DiffPath: String = ".orca/review.diff"

/** The resolver's answer: the target, plus the `<owner>/<repo>#<number>` ref
  * when it is a GitHub PR, which decides whether the report is also posted.
  */
case class Resolved(target: ReviewTarget, prRef: Option[String])
    derives JsonData

flow(OrcaArgs(args)):
  val resolved = stage("Resolve what to review"):
    resolveTarget()

  if resolved.target.changedFiles.isEmpty then
    fail(s"No changed files found for: ${resolved.target.summary}")

  display(
    s"Reviewing ${resolved.target.summary} — " +
      s"${resolved.target.changedFiles.size} file(s)"
  )

  val report = stage("Review"):
    reviewOnce(allReviewers(reviewAgent), resolved.target)

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

  // The diff is scratch, and this flow should leave the tree as it found it. A
  // failed run keeps the file deliberately: the resolve stage is skipped on
  // resume, so the reviewers re-read this same path.
  os.remove.all(os.pwd / os.RelPath(DiffPath))

/** Work out what the prompt refers to and leave its unified diff at
  * [[DiffPath]]. Written to disk rather than returned, so the diff never costs
  * output tokens.
  */
def resolveTarget()(using FlowContext, InStage): Resolved =
  val resolved = reviewAgent.cheap
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
         |Then report: `target.summary`, a one-line summary of what is under
         |review (e.g. "PR acme/widgets#42: add pagination"); `target.diffPath`,
         |which is always `$DiffPath`; `target.changedFiles`, the repo-relative
         |paths of the changed files; and `prRef`, only when the target is a
         |GitHub PR, its `<owner>/<repo>#<number>` ref.""".stripMargin
    )
  // The path is this flow's, not the agent's: reviewers read it and cleanup
  // removes it, so they must agree whatever the agent reports.
  resolved.copy(target = resolved.target.copy(diffPath = DiffPath))
