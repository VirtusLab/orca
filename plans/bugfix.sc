//> using dep "org.virtuslab::orca:0.0.3"
//> using jvm 21

/** Bugfix flow: red-test-first, against a real PR.
  *
  * Given a bug report (the user's prompt):
  *
  *   1. Triages — agent decides whether a failing test can reproduce.
  *   2. Lands a failing artefact (unit test, or REPRODUCTION.md if no test
  *      is possible) and commits it.
  *   3. Pushes a branch, opens a PR.
  *
  * When the artefact is a failing test, the flow continues:
  *
  *   4. Waits for CI red — fails the stage if CI is green (the agent's
  *      reproduction claim was wrong).
  *   5. Comments the failure on the PR; asks the agent to confirm the
  *      failure matches the report.
  *   6. Implements the fix on the same branch. Reviews + lints. Commits.
  *   7. Pushes the fix; waits for CI green.
  *
  * When no test is possible, the flow stops after step 3 with a docs-only
  * PR for a human to pick up.
  *
  * `examples/03-bugfix/create-test-project.sh` seeds the buggy project
  * into a temp directory and copies this script alongside it; push the
  * seed to a real GitHub repo first (the seeder prints the `gh repo
  * create` line) then run from the seeded directory the seeder prints:
  *
  * ```bash
  * scala-cli run bugfix.sc -- \
  *   "Calculator.add returns a wrong value when one argument is Integer.MIN_VALUE"
  * ```
  *
  * Requires `claude` and `gh` both authenticated; the target repo must have a
  * CI workflow that runs the test suite.
  */

import orca.{*, given}
import scala.concurrent.duration.DurationInt

flow(OrcaArgs(args)):

  // Interactive triage — the agent reads the report, explores the code, and
  // decides whether a unit test can reproduce or whether to document instead.
  val (session, triage) = stage("Triage the bug"):
    claude
      .resultAs[BugTriage]
      .interactive
      .run(
        s"""You are triaging this bug report:
           |
           |$userPrompt
           |
           |Decide:
           |  - Can a focused unit test reproduce this bug? If yes, set
           |    `canTest = true` and pick a path for the new test
           |    (`failingTestPath`).
           |  - If not, write reproduction steps in `reproductionSteps`
           |    instead.
           |
           |Pick a short kebab-case branch name and a one-line summary
           |suitable for a PR title.""".stripMargin
      )

  git.checkoutOrCreate(triage.branchName)

  // Land the failing artefact — a unit test, or REPRODUCTION.md.
  if triage.canTest then
    stage("Write the failing test"):
      val testPath = triage.failingTestPath.getOrElse(
        fail("triage.canTest = true but failingTestPath was missing")
      )
      val _ = claude.autonomous.run(
        s"""Now write the failing unit test at `$testPath`. The test
           |must FAIL on the current code — that's how we confirm the
           |bug. Run it locally if you can to verify it actually
           |fails.""".stripMargin,
        session = session
      )
      git.commit(s"Add failing test: ${triage.summary}").orThrow
  else
    stage("Document reproduction steps"):
      fs.write("REPRODUCTION.md", triage.reproductionSteps)
      git.commit(s"Document reproduction: ${triage.summary}").orThrow

  val pr = stage("Push and open PR"):
    git.push().orThrow
    gh.createPr(
      title = triage.summary,
      body = s"""## Bug
                |
                |$userPrompt
                |
                |## Reproduction
                |
                |${
                  if triage.canTest then
                    s"Failing test at `${triage.failingTestPath.get}`."
                  else triage.reproductionSteps
                }""".stripMargin
    ).orThrow

  // The rest of the flow — verify CI red, implement the fix, wait for CI
  // green — only applies when we have an executable repro. If we could only
  // produce REPRODUCTION.md, the PR is docs-only and a human picks it up.
  if triage.canTest then

    // CI green here means the agent's reproduction claim was wrong; surface
    // loudly rather than chasing a non-existent bug.
    val redBuild = stage("Wait for CI to fail"):
      val status = gh.waitForBuild(pr, 10.minutes).orThrow
      status.outcome match
        case BuildOutcome.Success =>
          fail(
            "CI passed on the failing-test commit. The reproduction " +
              "doesn't actually reproduce — re-triage and try again."
          )
        case _ => status

    stage("Comment on PR with the failure"):
      gh.writeComment(
        pr,
        s"CI red as expected:\n\n```\n${redBuild.log.take(2000)}\n```"
      )

    // Confirm the failure is the bug we set out to reproduce, not a
    // different test failure that coincidentally also went red.
    stage("Verify failure matches the report"):
      val (_, verdict) = claude
        .resultAs[BugReportMatch]
        .autonomous
        .run(
          s"""Here's the CI failure log:
             |
             |${redBuild.log}
             |
             |Does this match the original report? Be strict: a different
             |stack trace or a different assertion error counts as a
             |mismatch.""".stripMargin,
          session = session
        )
      if !verdict.matches then
        fail(s"Reproduction doesn't match the report: ${verdict.explanation}")

    // Fix + format + review in one commit so the PR history shows the
    // whole fix as a single entry.
    stage(s"Implement the fix: ${triage.summary}"):
      val _ = claude.autonomous.run(
        s"""The failing test is in place on branch `${triage.branchName}`.
           |Implement the fix. Ensure the previously-failing test now
           |passes, and that no other tests regress.""".stripMargin,
        session = session
      )

      // Format before review — Spotless is wired into the seed pom.
      stage("Format"):
        val _ = os
          .proc("mvn", "-q", "spotless:apply")
          .call(cwd = os.pwd, check = false)

      reviewAndFixLoop(
        coder = claude,
        sessionId = session,
        reviewers = allReviewers(claude),
        // Haiku picks the per-task reviewer subset; swap for
        // `ReviewerSelector.allEveryRound` to run every reviewer.
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = triage.summary,
        lintCommand = Some("mvn -q test"),
        lintLlm = Some(claude.haiku)
      )

      git.commit(s"Fix: ${triage.summary}").orThrow

    stage("Push the fix"):
      git.push().orThrow

    stage("Wait for CI to pass"):
      val status = gh.waitForBuild(pr, 15.minutes).orThrow
      if status.outcome != BuildOutcome.Success then
        fail(
          s"CI didn't go green after the fix. Last log:\n${status.log.take(2000)}"
        )
