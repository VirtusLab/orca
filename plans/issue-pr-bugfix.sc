//> using dep "org.virtuslab::orca:0.0.5"
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

      // Tentative description — only the failing test has landed. Haiku
      // folds the issue context + diff into the draft so the PR is
      // informative before the fix arrives. `git.diff()` (vs HEAD) would be
      // empty here since the failing test is already committed.
      val prSummary = stage("Generate tentative PR title and description"):
        summarisePr(
          llm = claude.haiku,
          diff = git.diffVsBase(git.defaultBase()),
          context = Some(
            s"""Originating issue: ${issueHandle.shortRef}
               |Issue title: ${issue.title}
               |
               |Note: this is a tentative description — only a failing
               |test has been added so far. The fix is still pending.""".stripMargin
          )
        )

      val pr = stage("Open PR"):
        gh.createPr(
          title = prSummary.title,
          body = s"""${prSummary.body}
                    |
                    |Closes ${issueHandle.shortRef}.""".stripMargin
        ).orThrow

      stage("Wait for CI to fail"):
        val status = gh.waitForBuild(pr, CiTimeout).orThrow
        if status.outcome == BuildOutcome.Success then
          fail(
            "CI passed on the failing-test commit. The reproduction " +
              "doesn't actually reproduce — re-triage and try again."
          )

      // Sonnet inspects the failed run via gh — the flow never pulls the
      // log into memory. Fresh sonnet session, shared across the two
      // sonnet turns below so the verifier can lean on what the
      // summariser found.
      val sonnetSession = claude.sonnet.newSession

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
             |it goes verbatim into a PR comment.""".stripMargin,
          session = sonnetSession
        )
        gh.writeComment(pr, failureSummary)

      stage("Verify failure matches the report"):
        val (_, verdict) =
          claude.sonnet.resultAs[BugReportMatch].autonomous.run(
            s"""Using the same failed run you inspected just now on PR
               |${pr.shortRef}, decide whether the failure matches the
               |original report below. Be strict — a different stack
               |trace or assertion error counts as a mismatch.
               |
               |Original report:
               |${issue.body}""".stripMargin,
            session = sonnetSession
          )
        if !verdict.matches then
          fail(s"Reproduction doesn't match the report: ${verdict.explanation}")

      // Plan + implement the fix. The flow's earlier stages (triage,
      // failing test push, CI-red wait, repro verification) aren't
      // restartable from a plan file alone, so no `.orca/plan-*.md` —
      // use the in-memory `implementTaskLoop` variant. The fix-plan's own
      // (read-only) planning session is discarded via `.value`; the fix
      // tasks run on the triage `session` opened above.
      val fixPlan = stage("Plan the fix"):
        Plan.autonomous.from(
          s"""Implement the fix for ${issueHandle.shortRef}. A failing
             |test is already on this branch (`$branchName`) — the fix
             |must make it pass without regressing other tests.""".stripMargin,
          claude
        ).value

      Plan.implementTaskLoop(fixPlan): task =>
        stage(s"Implement task: ${task.title}"):
          stage("Implementation"):
            val _ = claude.autonomous.run(task.description, session)
          // Format before review so reviewers don't burn turns on style
          // nits.
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
