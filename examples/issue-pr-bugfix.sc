//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.0.16"
//> using jvm 21

/** Bug-report → fix flow for Scala projects, autonomous.
  *
  * Given a `<owner>/<repo>#<number>` reference to a Scala-project issue, the
  * flow:
  *
  *   1. Reads the issue from GitHub (pure read, outside any stage).
  *   1. Triages: actually a bug? can a unit test reproduce it?
  *      - Not a bug → posts the verdict on the issue. The throwaway branch (no
  *        code committed) is auto-deleted by the runtime on exit.
  *      - Bug, but not testable → posts reproduction steps on the issue and
  *        stops (no PR for docs-only repros). Same branch cleanup.
  *   1. For a testable bug:
  *      a. "Write failing test" stage: writes and commits the test. b. "Push +
  *         open tentative PR" stage: pushes the committed test, then opens a PR
  *         (`gh.createPr` is idempotent by branch). These are two separate
  *         stages because a stage commits only on completion — a push in the
  *         same stage as the edit would push nothing (ADR 0018 §3.2 R8).
  *   1. Waits for CI to go red (pure polling read, outside a stage). Fails
  *      loudly on green — the reproduction is wrong.
  *   1. Confirms the failure matches the report.
  *   1. Plans + implements the fix on the same branch (each task a stage).
  *   1. "Push fix + finalise PR" stage: pushes the fix and regenerates the PR
  *      title + description from the full branch diff.
  *
  * The feature branch is named deterministically from the issue number
  * (`fix/issue-<n>`), so a re-run after a crash lands on the same branch.
  * Resume is stage-log based — each completed stage is skipped on a re-run.
  *
  * The flow reads top-to-bottom below; the per-step helper methods it calls
  * (`confirmReproductionMatches`, `planAndImplementFix`, `prSummary`) are
  * defined at the bottom of the file.
  *
  * Usage:
  *
  * ```bash
  * scala-cli run issue-pr-bugfix.sc -- "acme/widgets#42"
  * ```
  *
  * The review loop's format commands come from `.orca/settings.properties`,
  * auto-discovered on first run; the lint gate is pinned explicitly below to
  * demonstrate the per-call override.
  *
  * Requires `claude` and `gh` authenticated; target repo must have a CI
  * workflow that runs `sbt test`.
  */

import orca.{*, given}
import scala.concurrent.duration.DurationInt

// Parse the issue handle up-front so it can seed the deterministic branch
// naming strategy passed to `flow`. A parse failure exits before the flow.
val orcaArgs = OrcaArgs(args)
val issueHandle = IssueHandle.parseOrThrow(orcaArgs.userPrompt)

val CiTimeout = 30.minutes

// Opens a PR, so return to the starting branch afterward (the default is to
// stay on the feature branch, for no-PR flows).
flow(
  orcaArgs,
  branchNaming = Some(BranchNamingStrategy.issue(issueHandle)),
  returnToStartBranch = true
):
  // Pure read — outside any stage (reads don't need InStage).
  val issue = gh.readIssue(issueHandle)

  val issuePayload =
    s"""Title: ${issue.title}
       |Reporter: ${issue.author}
       |
       |${issue.body}""".stripMargin

  // Get-or-create the implementer session on the coding role before the
  // triage stage (pure: reserves the session id, no LLM call). The seed
  // primes it on first use and is replayed if the backend session is lost on
  // resume.
  val session = codingAgent.session("fixer", seed = issue.body)

  val triage: Triage = stage("Triage"):
    // Autonomous triage, read-only (read/grep to verify the report).
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
      // Write failing test: committed by the stage.
      stage("Write failing test"):
        session.run(
          s"""Write the failing unit test at `$failingTestPath`. It MUST
             |fail on the current code — that's how we confirm the bug.
             |Run `sbt test` locally if you can to verify.""".stripMargin
        )

      // Push + open PR: a SEPARATE stage from the edit above.
      // Authoring rule (ADR 0018 §3.2 R8): a stage commits only on completion,
      // so a push in the same stage as the edit would push nothing — the push
      // must be in a later stage than the code that produced it.
      val pr = stage("Push + open tentative PR"):
        git.push().orThrow
        // gh.createPr is idempotent by head branch (R24): if the branch already
        // has an open PR, the existing handle is returned.
        gh.createPr(
          title = summary,
          body = s"""Failing test only — fix pending.
                    |
                    |Closes ${issueHandle.shortRef}.""".stripMargin
        ).orThrow

      // `waitForBuild` is a pure polling read — outside any stage.
      if gh.waitForBuild(pr, CiTimeout).orThrow.outcome == BuildOutcome.Success
      then
        fail(
          "CI passed on the failing-test commit — the reproduction is wrong."
        )
      display(s"CI red on ${pr.shortRef} — reproduction confirmed")

      confirmReproductionMatches(pr, issue)
      planAndImplementFix(session)

      // Push fix + update PR: again a LATER stage than the task edits above.
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
// One def per step of the testable-bug pipeline; the `Testable` branch above
// reads as their sequence. Defined below the flow so the high-level logic comes
// first; each takes a `using` flow capability plus the `issue` / `session` it
// needs.

/** PR title + body from the full branch diff, with issue context and a
  * phase-specific `note`. Used for both the tentative (test-only) and final
  * (test + fix) descriptions. `git.diffVsBase` (not `git.diff()` vs HEAD)
  * because the changes are already committed.
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

/** Confirm the CI failure matches the original report. Each sub-stage is a
  * one-shot sonnet call — fresh session, no seed needed.
  */
def confirmReproductionMatches(pr: PrHandle, issue: Issue)(using
    FlowControl
): Unit =
  stage("Post focused failure comment"):
    val failureSummary = claude.sonnet.run(
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
      claude.sonnet
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

/** Plan + implement the fix on the same branch. The plan always carries a
  * brief (no separate `.briefed` step); `taskPrompt` prepends it to each task.
  * Implementation reuses the triage `session`.
  *
  * `[B <: BackendTag]` carries the coding agent's backend so the `session`
  * threads through the reviewers and `session.run`; it's read off the
  * `session` argument, letting the helper accept a session for whichever
  * backend the settings named.
  */
def planAndImplementFix[B <: BackendTag](
    session: FlowSession[B]
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

  for task <- fixPlan.tasks do
    stage(s"Task: ${task.title}"): // skipped on resume if already done
      session.run(fixPlan.taskPrompt(task))
      // reviewerSelection defaults to agentDriven — a picker LLM on
      // reviewAgent's cheap tier. Format defaults to the project's stack
      // settings (`.orca/settings.properties`).
      reviewAndFixLoop(
        coderSession = session,
        reviewers = allReviewers(reviewAgent),
        task = task.title.value,
        // An explicit override beats the settings file: pin the lint gate to
        // a compile (main + test) — a cheap sanity check; the failing test
        // runs in CI and correctness is the reviewers' job.
        lint = Configured.Use(Lint(List("sbt Test/compile"), codingAgent.cheap))
      )
      // one commit per task: code + progress entry
