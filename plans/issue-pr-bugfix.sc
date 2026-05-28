//> using dep "org.virtuslab::orca:0.0.4"
//> using jvm 21

/** Bug-report → fix flow for Scala projects, autonomous.
  *
  * Given a `<owner>/<repo>#<number>` reference to a Scala-project issue, the
  * flow:
  *
  *   1. Reads the issue from GitHub.
  *   1. Triages: actually a bug? can a unit test reproduce it?
  *       - Not a bug → comment the verdict on the issue and stop.
  *       - Bug, but not testable → comment reproduction steps on the issue
  *         and stop (no PR for docs-only repros).
  *   1. For a testable bug: write the failing test, push, open a PR with a
  *      tentative haiku-generated description noting only the failing test
  *      has landed.
  *   1. Wait `CiTimeout` for CI to go red — fail loudly on green (the
  *      reproduction is wrong).
  *   1. Sonnet inspects the failed run via `gh` (the flow never reads the
  *      log into memory) and posts a short focused failure comment.
  *   1. Sonnet verifies the failure matches the report — also via `gh`,
  *      by run id, not by log content.
  *   1. Plan + implement the fix on the same branch (read/grep, write,
  *      `sbt scalafmtAll`, review per task). Implementation reuses the
  *      triage/failing-test session.
  *   1. Push the fix. Does NOT wait for CI green at the end — a human
  *      picks the PR up from there.
  *
  * Usage:
  *
  * ```bash
  * scala-cli run issue-pr-bugfix.sc -- "acme/widgets#42"
  * ```
  *
  * Requires `claude` and `gh` authenticated; target repo must have a CI
  * workflow that runs `sbt test`.
  */

import orca.{*, given}
import scala.concurrent.duration.DurationInt

flow(OrcaArgs(args)):

  val CiTimeout = 30.minutes

  val issueHandle = IssueHandle.parseOrThrow(userPrompt)

  val issue = stage(s"Read issue ${issueHandle.shortRef}"):
    gh.readIssue(issueHandle)

  // Interactive triage so the agent can clarify edge cases. The session
  // returned here is reused below for the failing-test write and the fix
  // implementation, so the implementer inherits the triage's mental model.
  val (session, triage) = stage("Triage"):
    claude.opus.resultAs[BugTriage].interactive.run(
      s"""Triage this bug report:
         |
         |Title: ${issue.title}
         |Reporter: ${issue.author}
         |
         |${issue.body}
         |
         |Decide:
         |  - `isBug`: is this actually a defect (not intended behavior, user
         |    error, or out-of-scope)? If false, set `notBugExplanation` and
         |    leave the rest at empty defaults.
         |  - `canTest`: can a focused unit test reproduce this? If true, set
         |    `failingTestPath` (use the project's existing test framework
         |    and layout conventions) and pick a kebab-case `branchName`. Set
         |    `summary` to a one-line PR title.
         |  - If `isBug` is true but `canTest` is false, fill in
         |    `reproductionSteps` — they'll be posted back on the issue.""".stripMargin
    )

  if !triage.isBug then
    stage("Comment 'not a bug' on the issue"):
      gh.writeComment(issueHandle, triage.notBugExplanation)
  else if !triage.canTest then
    stage("Comment reproduction steps on the issue"):
      gh.writeComment(
        issueHandle,
        s"""## Reproduction
           |
           |${triage.reproductionSteps}""".stripMargin
      )
  else
    val testPath = triage.failingTestPath.getOrElse(
      fail("triage.canTest = true but failingTestPath was missing")
    )

    git.checkoutOrCreate(triage.branchName)

    stage("Write the failing test"):
      val _ = claude.autonomous.run(
        s"""Write the failing unit test at `$testPath`. It MUST fail on the
           |current code — that's how we confirm the bug. Run `sbt test`
           |locally if you can to verify.""".stripMargin,
        session = session
      )
      git.commit(s"Add failing test: ${triage.summary}").orThrow

    stage("Push branch"):
      git.push().orThrow

    // Tentative description — only the failing test has landed. Haiku folds
    // the issue context + diff into the draft so the PR is informative
    // before the fix arrives.
    val summary = stage("Generate tentative PR title and description"):
      summarisePr(
        llm = claude.haiku,
        diff = git.diff(),
        context = Some(
          s"""Originating issue: ${issueHandle.shortRef}
             |Issue title: ${issue.title}
             |
             |Note: this is a tentative description — only a failing test
             |has been added so far. The fix is still pending.""".stripMargin
        )
      )

    val pr = stage("Open PR"):
      gh.createPr(
        title = summary.title,
        body = s"""${summary.body}
                  |
                  |Closes ${issueHandle.shortRef}.""".stripMargin
      ).orThrow

    stage("Wait for CI to fail"):
      val status = gh.waitForBuild(pr, CiTimeout).orThrow
      if status.outcome == BuildOutcome.Success then
        fail(
          "CI passed on the failing-test commit. The reproduction doesn't " +
            "actually reproduce — re-triage and try again."
        )

    // Sonnet inspects the failed run via gh — the flow never pulls the log
    // into memory. Fresh sonnet session, shared across the two sonnet
    // turns below so the verifier can lean on what the summariser found.
    val sonnetSession = claude.sonnet.newSession

    stage("Post focused failure comment"):
      val (_, failureSummary) = claude.sonnet.autonomous.run(
        s"""CI went red on PR ${pr.shortRef} (${pr.url}). Inspect the failed
           |run via `gh` — start with:
           |
           |  gh pr checks ${pr.number} --repo ${pr.owner}/${pr.repo}
           |
           |then drill into the failing check with `gh run view <run-id>
           |--log-failed --repo ${pr.owner}/${pr.repo}`. Produce a short,
           |focused failure summary — the most informative excerpt, not the
           |whole log. Output only the summary text; it goes verbatim into a
           |PR comment.""".stripMargin,
        session = sonnetSession
      )
      gh.writeComment(pr, failureSummary)

    stage("Verify failure matches the report"):
      val (_, verdict) =
        claude.sonnet.resultAs[BugReportMatch].autonomous.run(
          s"""Using the same failed run you inspected just now on PR
             |${pr.shortRef}, decide whether the failure matches the
             |original report below. Be strict — a different stack trace or
             |assertion error counts as a mismatch.
             |
             |Original report:
             |${issue.body}""".stripMargin,
          session = sonnetSession
        )
      if !verdict.matches then
        fail(s"Reproduction doesn't match the report: ${verdict.explanation}")

    // Plan + implement the fix. Instruct the planner to reuse the branch
    // we're already on as the epic id so `recoverOrCreate`'s checkout is a
    // no-op (and a re-run keys off the same plan file).
    val planFile = Plan.defaultPath(userPrompt)
    val fixPlan = stage("Plan the fix"):
      Plan.recoverOrCreate(planFile, "orca: starting fix"):
        Plan.autonomous.from(
          s"""Implement the fix for ${issueHandle.shortRef}. A failing test
             |is already on this branch (`${triage.branchName}`) — the fix
             |must make it pass without regressing other tests. Use
             |`${triage.branchName}` as the epic id (the branch is already
             |checked out).""".stripMargin,
          claude
        )

    Plan.implementTaskLoop(planFile, fixPlan): task =>
      stage(s"Implement task: ${task.title}"):
        stage("Implementation"):
          val _ = claude.autonomous.run(task.description, session)
        // Format before review so reviewers don't burn turns on style nits.
        stage("Format"):
          val _ = os.proc("sbt", "scalafmtAll").call(check = false)
        reviewAndFixLoop(
          coder = claude,
          sessionId = session,
          reviewers = allReviewers(claude),
          reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
          task = task.title.value,
          lintCommand = Some("sbt test"),
          lintLlm = Some(claude.haiku)
        )

    stage("Push the fix"):
      git.push().orThrow
