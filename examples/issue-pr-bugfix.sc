//> using dep "org.virtuslab::orca:0.0.11"
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
  *      log into memory) and posts a short focused failure comment, then
  *      verifies the failure matches the report — also via `gh`, by run id.
  *   1. Plan + implement the fix on the same branch (read/grep, write,
  *      `sbt scalafmtAll`, review per task). Implementation reuses the
  *      triage/failing-test session.
  *   1. Push the fix, then regenerate the PR title + description from the
  *      full branch diff (so it reads as a fix, not "add a test"). Does NOT
  *      wait for CI green at the end — a human picks the PR up from there.
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

  // Autonomous triage — no human in the loop. Runs read-only (read/grep to
  // verify the report), but the session it returns is reused below for the
  // failing-test write and the fix: resuming it with a writable call restores
  // write access, and the implementer inherits the triage's exploration.
  val Sessioned(session, triage) = stage("Triage"):
    Plan.autonomous.triage(
      s"""Title: ${issue.title}
         |Reporter: ${issue.author}
         |
         |${issue.body}""".stripMargin,
      claude.opus
    )

  // ============================ pipeline phases ============================
  // Each phase below is one step of the testable-bug pipeline; the `Testable`
  // branch at the bottom reads as the sequence of these. They close over
  // `session`, `issue`, `issueHandle` and `CiTimeout`.

  /** Generate a PR title + body from the full branch diff, with the issue
    * context and a phase-specific `note` folded in. Used for both the tentative
    * (test-only) and final (test + fix) descriptions. `git.diffVsBase` (not
    * `git.diff()` vs HEAD) because the changes are already committed.
    */
  def prSummary(note: String): PrSummary =
    summarisePr(
      llm = claude.haiku,
      diff = git.diffVsBase(git.defaultBase()),
      context = Some(
        s"""Originating issue: ${issueHandle.shortRef}
           |Issue title: ${issue.title}
           |
           |$note""".stripMargin
      )
    )

  def writeAndPushFailingTest(summary: String, failingTestPath: String): Unit =
    stage("Write the failing test"):
      val _ = claude.autonomous.run(
        s"""Write the failing unit test at `$failingTestPath`. It MUST
           |fail on the current code — that's how we confirm the bug.
           |Run `sbt test` locally if you can to verify.""".stripMargin,
        session = session
      )
      git.commit(s"Add failing test: $summary").orThrow
    stage("Push branch"):
      git.push().orThrow

  /** Open the PR with a tentative description — only the failing test has
    * landed, so the body says so explicitly.
    */
  def openTentativePr(): PrHandle =
    val prSum = stage("Generate tentative PR title and description"):
      prSummary(
        "Note: this is a tentative description — only a failing test has " +
          "been added so far. The fix is still pending."
      )
    stage("Open PR"):
      gh.createPr(
        title = prSum.title,
        body = s"""${prSum.body}
                  |
                  |Closes ${issueHandle.shortRef}.""".stripMargin
      ).orThrow

  /** The failing test must turn CI red; a green run means the reproduction is
    * wrong, so fail loudly.
    */
  def awaitRedCi(pr: PrHandle): Unit =
    stage("Wait for CI to fail"):
      val status = gh.waitForBuild(pr, CiTimeout).orThrow
      if status.outcome == BuildOutcome.Success then
        fail(
          "CI passed on the failing-test commit. The reproduction " +
            "doesn't actually reproduce — re-triage and try again."
        )

  /** Sonnet inspects the failed run via `gh` — the flow never pulls the log
    * into memory. Each sonnet turn is a fresh one-shot session (only the
    * implementer holds a long-lived session), so each re-inspects the run by id
    * and is self-contained. Posts a focused failure comment, then strictly
    * verifies the failure matches the original report.
    */
  def confirmReproductionMatches(pr: PrHandle): Unit =
    stage("Post focused failure comment"):
      val (_, failureSummary) = claude.sonnet.autonomous.run(
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
      gh.writeComment(pr, failureSummary)

    stage("Verify failure matches the report"):
      val (_, verdict) =
        claude.sonnet.resultAs[BugReportMatch].autonomous.run(
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

  /** Plan + implement the fix on the same branch. No `.orca/plan-*.md`: the
    * earlier stages (triage, CI red, repro verification) aren't restartable from
    * a plan file alone, so use the in-memory `implementTaskLoop`. The draft plan
    * is self-reviewed and given a codebase brief (`reviewed`/`briefed`, both
    * resuming the planning session read-only); that planning session is then
    * discarded via `.value`, and the fix tasks — each carrying the brief via
    * `taskPrompt` — run on the triage `session`.
    */
  def planAndImplementFix(branchName: String): Unit =
    val fixPlan = stage("Plan the fix"):
      Plan.autonomous.from(
        s"""Implement the fix for ${issueHandle.shortRef}. A failing
           |test is already on this branch (`$branchName`) — the fix
           |must make it pass without regressing other tests.""".stripMargin,
        claude
      ).reviewed(claude).briefed(claude).value

    Plan.implementTaskLoop(fixPlan): task =>
      stage(s"Implement task: ${task.title}"):
        stage("Implementation"):
          val _ = claude.autonomous.run(fixPlan.taskPrompt(task), session)
        reviewAndFixLoop(
          coder = claude,
          sessionId = session,
          reviewers = allReviewers(claude),
          reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
          task = task.title.value,
          // Format after every edit (the implementation and each review fix).
          formatCommand = Some("sbt scalafmtAll"),
          // A compile (main + test sources) is a cheap sanity gate for the
          // reviewers; the failing test runs in CI and correctness is the
          // reviewers' job, so don't run the (much heavier) full suite.
          lintCommand = Some("sbt Test/compile"),
          lintLlm = Some(claude.haiku)
        )

  /** Push the fix and regenerate the PR title + body from the full branch diff,
    * so the PR now reads as a fix rather than "add a test". Does not wait for CI
    * green — a human takes it from here.
    */
  def pushAndFinalisePr(pr: PrHandle): Unit =
    stage("Push the fix"):
      git.push().orThrow
    stage("Update PR title and description"):
      val finalSum = prSummary(
        "The branch now contains both the failing test and the fix " +
          "that makes it pass."
      )
      gh.updatePr(
        pr,
        title = finalSum.title,
        body = s"""${finalSum.body}
                  |
                  |Closes ${issueHandle.shortRef}.""".stripMargin
      )

  // ============================ the flow ============================

  triage match
    case Triage.NotABug(explanation) =>
      stage("Comment 'not a bug' on the issue"):
        gh.writeComment(issueHandle, explanation)

    case Triage.Untestable(_, reproductionSteps) =>
      stage("Comment reproduction steps on the issue"):
        gh.writeComment(
          issueHandle,
          s"""## Reproduction
             |
             |$reproductionSteps""".stripMargin
        )

    case Triage.Testable(summary, branchName, failingTestPath) =>
      // Stash pre-existing local edits before switching branches — otherwise
      // they'd ride onto the bugfix branch and get folded into the
      // failing-test commit. `ensureClean` emits a Step the user can act on
      // (`git stash pop`) once the flow finishes.
      val _ = git.ensureClean("orca: pre-bugfix stash")
      git.checkoutOrCreate(branchName)

      writeAndPushFailingTest(summary, failingTestPath)
      val pr = openTentativePr()
      awaitRedCi(pr)
      confirmReproductionMatches(pr)
      planAndImplementFix(branchName)
      pushAndFinalisePr(pr)
