// Bug report (owner/repo#N or URL) → repro test, fix, PR — or a triage comment.
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.1.4"
//> using jvm 21

/** Bug-report → fix flow, autonomous.
  *
  * The bug-report counterpart of `issue-pr.sc`: where that flow assesses an
  * issue and plans straight into an implementation, this one insists on a
  * reproduction first — a failing test that CI confirms is red — and only then
  * fixes it.
  *
  * Three outcomes, decided by triage: a comment saying it isn't a bug; a
  * comment with reproduction steps when it is one but no test can show it; or
  * a PR carrying the failing test and the fix that makes it pass. Only the
  * third writes any code.
  *
  * Given a `<owner>/<repo>#<number>` reference to an issue, or its GitHub URL,
  * the flow:
  *
  *   1. Reads the issue from GitHub.
  *   1. Triages: actually a bug? can a unit test reproduce it?
  *      - Not a bug → posts the verdict on the issue.
  *      - Bug, but not testable → posts reproduction steps on the issue and
  *        stops (no PR for docs-only repros).
  *   1. For a testable bug: writes and commits the failing test, then pushes
  *      it and opens a tentative PR.
  *   1. Waits for CI to go red. Fails loudly on green — the reproduction is
  *      wrong.
  *   1. Confirms the failure matches the report.
  *   1. Plans + implements the fix on the same branch, reviewing each task in
  *      a single pass, then loops a review over everything the run changed.
  *   1. Pushes the fix and regenerates the PR title + description from the
  *      full branch diff.
  *
  * The feature branch is named deterministically from the issue number
  * (`fix/issue-<n>`), so a re-run after a crash lands on the same branch.
  *
  * The flow reads top-to-bottom below; the per-step helpers
  * (`confirmReproductionMatches`, `planAndImplementFix`, `prSummary`) are
  * defined at the bottom of the file.
  *
  * Usage — pass `<owner>/<repo>#<number>` or the issue's github.com URL (no
  * query string or `#...` anchor):
  *
  * ```bash
  * scala-cli run issue-pr-bugfix.sc -- "acme/widgets#42"
  * scala-cli run issue-pr-bugfix.sc -- "https://github.com/acme/widgets/issues/42"
  * ```
  *
  * Use the same form on re-runs: the progress log and the issue-comment marker
  * are keyed on the prompt text, so switching between the two starts a fresh
  * run and can post a duplicate comment.
  *
  * Requires `gh` authenticated, and the configured role agents logged in
  * (`claude` by default).
  *
  * The target repo must have a CI workflow that runs its test suite: a red
  * build is what confirms the reproduction.
  */

import orca.{*, given}
import scala.concurrent.duration.DurationInt

// Parse the issue handle up-front so it can seed the deterministic branch
// naming strategy passed to `flow`. A parse failure exits before the flow.
val orcaArgs = OrcaArgs(args)
val issueHandle = IssueHandle.parseOrThrow(orcaArgs.userPrompt)

val CiTimeout = 30.minutes

// Opens a PR, so return to the starting branch afterward.
flow(
  orcaArgs,
  branchNaming = Some(BranchNamingStrategy.issue(issueHandle)),
  returnToStartBranch = true
):
  val issue = gh.readIssue(issueHandle)

  val issuePayload =
    s"""Title: ${issue.title}
       |Reporter: ${issue.author}
       |
       |${issue.body}""".stripMargin

  val session = codingAgent.session("fixer", seed = issue.body)

  val triage: Triage = stage("Triage"):
    // Read-only: the triager reads/greps to verify the report, changes nothing.
    Plan.autonomous.triage(issuePayload, planningAgent).value

  triage match
    case Triage.NotABug(explanation) =>
      stage("Comment: not a bug"):
        gh.upsertComment(
          issueHandle,
          orcaCommentMarker(userPrompt, "not-a-bug"),
          explanation
        )

    case Triage.Untestable(_, reproductionSteps) =>
      stage("Comment: repro steps"):
        gh.upsertComment(
          issueHandle,
          orcaCommentMarker(userPrompt, "repro-steps"),
          s"""## Reproduction
             |
             |$reproductionSteps""".stripMargin
        )

    case Triage.Testable(summary, _, failingTestPath) =>
      stage("Write failing test"):
        session.run(
          s"""Write the failing unit test at `$failingTestPath`. It MUST
             |fail on the current code — that's how we confirm the bug.
             |Run the project's test suite locally if you can to
             |verify.""".stripMargin
        )

      // A later stage than the edit above, so the test commit exists to push.
      val pr = stage("Push + open tentative PR"):
        git.push().orThrow
        gh.createPr(
          title = summary,
          body = s"""Failing test only — fix pending.
                    |
                    |Closes ${issueHandle.shortRef}.""".stripMargin
        ).orThrow

      if gh.waitForBuild(pr, CiTimeout).orThrow.outcome == BuildOutcome.Success
      then
        fail(
          "CI passed on the failing-test commit — the reproduction is wrong."
        )
      display(s"CI red on ${pr.shortRef} — reproduction confirmed")

      confirmReproductionMatches(pr, issue)
      planAndImplementFix(session, issuePayload)

      // Again later than the task edits above, so the fix commits exist.
      stage("Push fix + finalise PR"):
        git.push().orThrow
        val finalSum = prSummary(
          "The branch now contains both the failing test and the fix " +
            "that makes it pass.",
          issue
        )
        gh.updatePr(
          pr,
          title = finalSum.title,
          body = s"""${finalSum.body}
                    |
                    |Closes ${issueHandle.shortRef}.""".stripMargin
        )

// ============================ pipeline helpers ============================

/** PR title + body from the full branch diff, with issue context and a
  * phase-specific `note`. Used for both the tentative (test-only) and final
  * (test + fix) descriptions.
  */
def prSummary(note: String, issue: Issue)(using
    FlowContext,
    InStage
): PrSummary =
  summarisePr(
    agent = codingAgent.cheap,
    diff = git.diffVsBase(git.defaultBase()),
    context = Some(
      s"""Originating issue: ${issueHandle.shortRef}
         |Issue title: ${issue.title}
         |
         |$note""".stripMargin
    )
  )

/** Confirm the CI failure matches the original report. Both sub-stages are
  * one-shot calls on the coding role — fresh session, no seed needed. The
  * excerpt-picking is cheap-tier work; the verdict is not, since a wrong
  * "matches" lets a bogus reproduction through and a wrong "doesn't" aborts a
  * sound one.
  */
def confirmReproductionMatches(pr: PrHandle, issue: Issue)(using
    FlowControl
): Unit =
  stage("Post focused failure comment"):
    val failureSummary = codingAgent.cheap.run(
      s"""CI went red on PR ${pr.shortRef} (${pr.url}). Inspect the
         |failed run via `gh` — start with:
         |
         |  gh pr checks ${pr.number} --repo ${pr.owner}/${pr.repo}
         |
         |then drill into the failing check with `gh run view <run-id>
         |--log-failed --repo ${pr.owner}/${pr.repo}`. Produce a
         |short, focused failure summary — the most informative
         |excerpt, not the whole log. Output only the summary text;
         |it goes verbatim into a PR comment.""".stripMargin
    )
    gh.upsertComment(
      pr,
      orcaCommentMarker(userPrompt, "ci-failure"),
      failureSummary
    )

  stage("Verify failure matches the report"):
    val verdict =
      codingAgent
        .resultAs[BugReportMatch]
        .autonomous
        .run(
          s"""Inspect the failed CI run on PR ${pr.shortRef} via `gh`
           |(`gh pr checks ${pr.number} --repo ${pr.owner}/${pr.repo}`,
           |then `gh run view <run-id> --log-failed`), then decide whether
           |the failure matches the original report below. Be strict — a
           |different stack trace or assertion error counts as a mismatch.
           |
           |Original report:
           |${issue.body}""".stripMargin
        )
    if !verdict.matches then
      fail(s"Reproduction doesn't match the report: ${verdict.explanation}")

/** Plan + implement the fix on the same branch, reusing the triage `session`,
  * then review everything the run changed.
  *
  * `[B <: BackendTag]` is read off the `session` argument, so the helper
  * accepts a session for whichever backend the settings named.
  *
  * `issuePayload` is what reviewers are shown as the user's request: the run's
  * prompt is only an issue reference.
  */
def planAndImplementFix[B <: BackendTag](
    session: FlowSession[B],
    issuePayload: String
)(using FlowControl): Unit =
  val fixPlan = stage("Plan the fix"):
    Plan.autonomous
      .from(
        s"""Implement the fix for ${issueHandle.shortRef}. A failing
           |test is already on this branch — the fix must make it pass
           |without regressing other tests.""".stripMargin,
        planningAgent
      )
      .reviewed(planningAgent)
      .value

  val taskDeclines =
    for task <- fixPlan.tasks yield
      stage(s"Task: ${task.title}"):
        session.run(fixPlan.taskPrompt(task))
        // Don't gate this review on the tests: the branch carries a
        // deliberately failing one until the last fix task lands.
        reviewThenFix(
          coderSession = session,
          reviewers = allReviewers(reviewAgent),
          task = task,
          userRequest = Some(issuePayload)
        )

  // Everything the run changed — the failing test and the fix alike — reviewed
  // in one loop: each task's single pass took the fixer's word for its own
  // fixes, and this is what checks them. The per-task declines seed the loop,
  // so its reviewers don't re-report findings the fixer already answered.
  stage("Final review"):
    reviewAndFixLoop(
      coderSession = session,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title(s"Fix for ${issueHandle.shortRef}"), fixPlan.brief),
      userRequest = Some(issuePayload),
      diff = ReviewDiff.WholeRun,
      maxIterations = 3,
      priorDeclines = IgnoredIssues(taskDeclines.flatMap(_.issues))
    )
