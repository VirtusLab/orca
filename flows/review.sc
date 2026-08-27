// Review a PR, a branch, or local changes — a list of findings, no fixes.
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.1.5"
//> using jvm 21

/** Review-only flow: no planning, no coding, nothing committed.
  *
  * The prompt says what to review, in whatever form suits: a PR reference or
  * URL, a branch name, "the uncommitted changes", a commit range, or a diff
  * piped straight in (`git diff | orca run review.sc`). A resolver stage works
  * out what that refers to and materialises the diff once; from there the flow:
  *
  *   1. Narrows the shipped reviewer roster to the ones whose `files:` pattern
  *      matches the changed paths, then has a cheap-tier agent pick from what's
  *      left.
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
  * `simple.sc`.
  *
  * ```bash
  * scala-cli run review.sc -- "acme/widgets#42"
  * scala-cli run review.sc -- "the uncommitted changes"
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

/** What the resolver worked out, minus the diff itself. `prRef` is the
  * `<owner>/<repo>#<number>` form when the target is a GitHub PR, absent
  * otherwise — it decides whether the report is posted as well as printed.
  */
case class ReviewTarget(
    summary: String,
    changedFiles: List[String],
    prRef: Option[String]
) derives JsonData

/** Single-property envelope around [[ReviewTarget]]: a cheap-tier model handed
  * a multi-property result schema tends to stuff everything under the first
  * property. Same reason [[PickedReviewers]] carries a single list.
  */
case class ResolvedTarget(target: ReviewTarget) derives JsonData

case class PickedReviewers(names: List[String]) derives JsonData

/** One reviewer's findings, named so the report can attribute each issue. */
case class ReviewerFindings(reviewer: String, issues: List[ReviewIssue])
    derives JsonData

case class AllFindings(byReviewer: List[ReviewerFindings]) derives JsonData

flow(OrcaArgs(args)):
  val target = stage("Resolve what to review"):
    resolveTarget().target

  if target.changedFiles.isEmpty then
    fail(s"No changed files found for: ${target.summary}")

  display(s"Reviewing ${target.summary} — ${target.changedFiles.size} file(s)")

  val picked = stage("Pick reviewers"):
    PickedReviewers(pickReviewers(target).map(_.name))

  val findings = stage("Run reviewers"):
    val agents = buildReviewers(
      reviewAgent,
      ReviewerPrompts.all.filter(r => picked.names.contains(r.name))
    )
    // Results come back in completion order, hence the pairing with the
    // reviewer's name.
    AllFindings(Par.mapUnordered(4)(agents): a =>
      ReviewerFindings(
        a.name,
        a.resultAs[ReviewResult].autonomous.run(reviewPrompt(target)).issues
      ))

  val report = renderReport(target, findings.byReviewer)
  display(report)

  target.prRef.foreach: ref =>
    stage("Post report on the PR"):
      val issue = IssueHandle.parseOrThrow(ref)
      gh.upsertComment(
        PrHandle(issue.owner, issue.repo, issue.number),
        orcaCommentMarker(userPrompt, "review"),
        report
      )

  // The diff is scratch, and this flow should leave the tree as it found it. A
  // failed run keeps the file deliberately: the resolve stage is skipped on
  // resume, so the reviewers re-read this same path.
  os.remove.all(os.pwd / os.RelPath(DiffPath))

// ============================== flow helpers ==============================

/** Work out what the prompt refers to and leave its unified diff at
  * [[DiffPath]]. Written to disk rather than returned, so the diff never costs
  * output tokens.
  */
def resolveTarget()(using FlowContext, InStage): ResolvedTarget =
  reviewAgent.cheap
    .resultAs[ResolvedTarget]
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
         |Then report: a one-line summary of what is under review (e.g. "PR
         |acme/widgets#42: add pagination"), the repo-relative paths of the
         |changed files, and — only when the target is a GitHub PR — its
         |`<owner>/<repo>#<number>` ref.""".stripMargin
    )

/** The reviewers worth running: the roster narrowed by each reviewer's `files:`
  * pattern, then by a cheap-tier pick over what survives. Falls back to the
  * pattern-matched set if the pick comes back empty or names nothing real — a
  * miscounted pick should under-select, never review nothing.
  */
def pickReviewers(target: ReviewTarget)(using
    FlowContext,
    InStage
): List[Reviewer] =
  val candidates = ReviewerPrompts.all.filter: r =>
    ReviewerPrompts.filePatternsBySlug
      .get(r.name)
      .forall(p => target.changedFiles.exists(f => p.findFirstIn(f).isDefined))

  val listing = candidates
    .map(r => s"- ${r.name}: ${r.description}")
    .mkString("\n")

  val picked = reviewAgent.cheap
    .resultAs[PickedReviewers]
    .autonomous
    .run(
      s"""Pick the reviewers whose dimension is relevant to the change below.
         |The goal is to skip the ones that clearly don't apply, not to run
         |them all — but when several apply, name all of them. Copy each name
         |verbatim.
         |
         |Under review: ${target.summary}
         |
         |Changed files:
         |${target.changedFiles.mkString("\n")}
         |
         |Available reviewers:
         |$listing""".stripMargin
    )

  candidates.filter(c => picked.names.contains(c.name)) match
    case Nil      => candidates
    case selected => selected

/** What each reviewer is asked: findings scoped to the change, with location
  * and a suggested fix — reading the diff off disk, since a read-only reviewer
  * cannot produce one.
  */
def reviewPrompt(target: ReviewTarget): String =
  s"""Under review: ${target.summary}
     |
     |The complete diff is in `$DiffPath` — read it first. Review only what it
     |changes, plus the code that interacts directly with it; you may read
     |anything in the repository to check a claim, but do not report issues in
     |code this change doesn't touch.
     |
     |Report each finding with: a one-line title, a description with enough
     |context to act on, the file and line where applicable, and a concrete
     |suggested fix. Report only what is worth acting on — no nitpicks, no
     |restating what the change already does well. If nothing in your dimension
     |applies, report no issues."""
    .stripMargin

// ============================== report ==============================

/** The whole report as markdown — printed to the console, and posted verbatim
  * when the target is a PR. One flat list: findings keep the order the
  * reviewers reported them in, which is the only ordering key the flow has.
  */
def renderReport(
    target: ReviewTarget,
    byReviewer: List[ReviewerFindings]
): String =
  val attributed = byReviewer.flatMap(f => f.issues.map(i => f.reviewer -> i))
  val header =
    s"## Review: ${target.summary}\n\n" +
      s"${attributed.size} finding(s) from ${byReviewer.size} reviewer(s) " +
      s"across ${target.changedFiles.size} changed file(s)."

  if attributed.isEmpty then s"$header\n\nNo issues reported."
  else s"$header\n\n${attributed.map(renderIssue).mkString("\n")}"

def renderIssue(attributed: (String, ReviewIssue)): String =
  val (reviewer, issue) = attributed
  val where = issue.location
    .map:
      case Location(file, Some(line)) => s" — `$file:$line`"
      case Location(file, None)       => s" — `$file`"
    .getOrElse("")
  val suggestion = issue.suggestion.fold("")(s => s"\n  - suggestion: $s")
  s"- **${issue.title}** ($reviewer)$where\n" +
    s"  - ${issue.description}$suggestion"
