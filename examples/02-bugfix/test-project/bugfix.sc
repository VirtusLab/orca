//> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

/** Bugfix flow: red-test-first, against a real PR.
  *
  * Given a bug report (the user's prompt), the flow:
  *
  *   1. Triages the bug — interactively asks the agent whether a
  *      failing test can reproduce it, what the reproduction steps
  *      are otherwise, and where to put any reproduction artefact.
  *   2. Writes the failing artefact (a unit test if possible, a
  *      `REPRODUCTION.md` otherwise) and commits it.
  *   3. Pushes a branch, opens a PR.
  *   4. Waits for CI to come back red — fails the stage if CI was
  *      green (the agent claimed the test reproduced; CI says
  *      otherwise).
  *   5. Comments on the PR with the failing-test output, and asks
  *      the agent to confirm the failure matches the original
  *      report. Fails the stage on a mismatch.
  *   6. Implements the fix on the same branch. Reviews + lints.
  *   7. Pushes the fix; waits for CI to go green.
  *
  * Lives alongside the seeded buggy project so a user can run it
  * from the project's root after `examples/02-bugfix/create-test-project.sh`
  * (push the seed to a real GitHub repo first — the seed script
  * prints the `gh repo create` line):
  *
  * ```bash
  * scala-cli run bugfix.sc -- \
  *   "Calculator.add returns a wrong value when one argument is Integer.MIN_VALUE"
  * ```
  *
  * Requires `claude` and `gh` both authenticated; the target repo
  * must have a CI workflow that runs the test suite.
  */

import orca.{*, given}
import scala.concurrent.duration.DurationInt

case class BugTriage(
    /** True if a focused unit test can reproduce the bug. */
    canTest: Boolean,
    /** Free-form reproduction steps the agent inferred from the
      * report — used when `canTest` is false (we can't write a test,
      * so we document instead).
      */
    reproductionSteps: String,
    /** Path of the test file the agent will create when `canTest`
      * is true. None otherwise.
      */
    failingTestPath: Option[String],
    /** Short branch name. Lowercase kebab-case; no spaces. */
    branchName: String,
    /** One-line summary suitable for the PR title. */
    summary: String
) derives JsonData

object BugTriage:
  given Announce[BugTriage] = Announce.from: t =>
    val approach =
      if t.canTest then s"failing test at ${t.failingTestPath.getOrElse("?")}"
      else "documenting reproduction steps"
    s"Triage: ${t.summary} — $approach on branch '${t.branchName}'"

case class BugReportMatch(
    /** Whether the failing-test output (or reproduction artefact)
      * is a faithful reproduction of what the original report
      * described.
      */
    matches: Boolean,
    /** Short justification for the verdict. */
    explanation: String
) derives JsonData

object BugReportMatch:
  given Announce[BugReportMatch] = Announce.from: m =>
    val verdict = if m.matches then "Reproduction confirmed" else "Reproduction MISMATCH"
    s"$verdict: ${m.explanation}"

flow(OrcaArgs(args)):

  // 1. Triage the bug interactively. The agent reads the report,
  // explores the code, and tells us how to reproduce.
  val (sessionId, triage) = stage("Triage the bug"):
    claude.resultAs[BugTriage].interactive(
      s"""You are triaging this bug report:
         |
         |$userPrompt
         |
         |Decide:
         |  - Can a focused unit test reproduce this bug? If yes, set
         |    `canTest = true` and pick a path for the new test
         |    (`failingTestPath`).
         |  - If not, write reproduction steps in
         |    `reproductionSteps` instead.
         |
         |Pick a short kebab-case branch name and a one-line summary
         |suitable for a PR title.""".stripMargin
    )

  git.checkoutOrCreate(triage.branchName)

  // 2. Land the failing artefact: a unit test, or a REPRODUCTION.md.
  if triage.canTest then
    stage("Write the failing test"):
      val testPath = triage.failingTestPath.getOrElse(
        fail("triage.canTest = true but failingTestPath was missing")
      )
      val _ = claude.continueSession(
        sessionId,
        s"""Now write the failing unit test at `$testPath`. The test
           |must FAIL on the current code — that's how we confirm the
           |bug. Run it locally if you can to verify it actually
           |fails.""".stripMargin
      )
      git.commit(s"Add failing test: ${triage.summary}")
  else
    stage("Document reproduction steps"):
      fs.write("REPRODUCTION.md", triage.reproductionSteps)
      git.commit(s"Document reproduction: ${triage.summary}")

  // 3. Push & open the PR.
  val pr = stage("Push and open PR"):
    git.push()
    gh.createPr(
      title = triage.summary,
      body =
        s"""## Bug
           |
           |$userPrompt
           |
           |## Reproduction
           |
           |${if triage.canTest then s"Failing test at `${triage.failingTestPath.get}`."
            else triage.reproductionSteps}""".stripMargin
    )

  // 4. Wait for CI. If CI is green here, the agent's "this fails" is
  // wrong — surface it loudly rather than silently moving on.
  val redBuild = stage("Wait for CI to fail"):
    val status = gh.waitForBuild(pr, 10.minutes)
    status.outcome match
      case BuildOutcome.Success =>
        fail(
          "CI passed on the failing-test commit. The reproduction " +
            "doesn't actually reproduce — re-triage and try again."
        )
      case _ => status

  // 5. Comment on the PR with the failure, then verify it really is
  // the bug we set out to reproduce.
  stage("Comment on PR with the failure"):
    gh.writeComment(
      pr,
      s"CI red as expected:\n\n```\n${redBuild.log.take(2000)}\n```"
    )

  stage("Verify failure matches the report"):
    val verdict = claude.resultAs[BugReportMatch].continueSession(
      sessionId,
      s"""Here's the CI failure log:
         |
         |${redBuild.log}
         |
         |Does this match the original report? Be strict: a
         |different stack trace or a different assertion error
         |counts as a mismatch.""".stripMargin
    )
    if !verdict.matches then
      fail(s"Reproduction doesn't match the report: ${verdict.explanation}")

  // 6. Implement the fix. Autonomous — continues the planning
  // session so the agent already has context. The reviewers run
  // after to clean up.
  stage(s"Implement the fix: ${triage.summary}"):
    val _ = claude.continueSession(
      sessionId,
      s"""The failing test (or documented reproduction) is in place
         |on branch `${triage.branchName}`. Implement the fix.
         |Ensure the previously-failing test now passes, and that no
         |other tests regress.""".stripMargin
    )
    git.commit(s"Fix: ${triage.summary}")

    reviewAndFixLoop(
      coder = claude,
      sessionId = sessionId,
      reviewers = defaultReviewers(claude),
      task = triage.summary,
      lintCommand = Some("mvn -q test")
    )

  // 7. Push and wait for green.
  stage("Push the fix"):
    git.push()

  stage("Wait for CI to pass"):
    val status = gh.waitForBuild(pr, 15.minutes)
    if status.outcome != BuildOutcome.Success then
      fail(s"CI didn't go green after the fix. Last log:\n${status.log.take(2000)}")
