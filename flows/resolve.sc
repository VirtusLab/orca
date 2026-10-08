// Resolve a request or GitHub issue: triage, reproduce a bug, fix or build it, PR.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.10"
//> using jvm 21

/** Request → triage → fix or change → PR, fully autonomous.
  *
  * The prompt is any request: a bug report, a feature request, a change — or a
  * GitHub issue reference (`<owner>/<repo>#<number>` or the issue's URL), in
  * which case the issue is read and the branch is named after it
  * (`fix/issue-<n>`), so a re-run after a crash lands on the same branch.
  *
  * Triage checks the request against the repository and either rejects it — the
  * reply is posted on the issue, or printed — or accepts it as one of:
  *
  *   - a bug a test can show: a failing test is written and checked to fail
  *     the way the request describes, before anything is fixed;
  *   - a bug no test can show: fixed anyway; the PR (and the issue) say there
  *     is no automated reproduction and list the steps;
  *   - a change: planned and implemented directly.
  *
  * Accepted work is planned, implemented task by task with a single review pass
  * each, reviewed as a whole in a loop, and opened as a PR when the repository
  * is on GitHub.
  *
  * ```bash
  * scala-cli run --workspace "$(mktemp -d)" resolve.sc -- "acme/widgets#42"
  * scala-cli run --workspace "$(mktemp -d)" resolve.sc -- "Dividing by zero crashes the calculator"
  * ```
  *
  * Use the same prompt on re-runs: the progress log and the issue-comment
  * marker are keyed on it.
  *
  * Requires the configured role agents logged in (`claude` by default); `gh`
  * when the prompt is an issue reference.
  */

import orca.{*, given}

val orcaArgs = OrcaArgs(args)

/** The issue the prompt names, when the whole prompt is an issue reference. A
  * PR URL also parses as one, but a PR is not a request to resolve.
  */
val issueHandle: Option[IssueHandle] =
  if orcaArgs.userPrompt.contains("/pull/") then None
  else IssueHandle.parse(orcaArgs.userPrompt).toOption

/** Write a test at `testPath` that fails the way `request` describes, and have
  * a second agent confirm it. The reproducer retries once when the check
  * disagrees; a second disagreement fails the stage, so a re-run starts over.
  */
def reproduce(request: String, testPath: String)(using
    FlowContext,
    FlowControl,
    InStage,
    WorkspaceWrite
): Unit =
  val reproducer = codingAgent.session("reproducer", seed = request)
  reproducer.run(
    s"""Write a focused test at `$testPath` that fails on the current code in
       |the way the request describes. Run only that test and confirm it
       |fails.""".stripMargin
  )
  val first = checkReproduction(request, testPath)
  if !first.matches then
    reproducer.run(
      s"""A separate check says the test does not reproduce the request:
         |${first.explanation}
         |
         |Fix the test at `$testPath`, then run it again.""".stripMargin
    )
    val second = checkReproduction(request, testPath)
    if !second.matches then
      fail(
        s"Could not reproduce the request: ${second.explanation}. Add " +
          "reproduction details to the request (or the issue) and re-run; " +
          "the Reproduce stage starts over."
      )

/** Run the test at `testPath` and judge whether its failure is the one
  * `request` describes. Full tier: a wrong "matches" lets a bogus reproduction
  * through, a wrong "doesn't" aborts a sound one.
  */
def checkReproduction(request: String, testPath: String)(using
    FlowContext,
    InStage
): BugReportMatch =
  val testHint = summon[FlowContext].stackSettings.test match
    case Nil      => ""
    case commands => s"\nThe project's test command: ${commands.mkString(" && ")}"
  codingAgent
    .resultAs[BugReportMatch]
    .autonomous
    .run(
      s"""Run only the test at `$testPath`. It must fail, and the failure must
         |be the defect the request below describes — a passing test, or a
         |failure for another reason (compile error, wrong assertion), does not
         |match.$testHint
         |
         |Request:
         |$request""".stripMargin
    )

/** What the planner is asked: the request, triage's findings, and for a
  * testable bug the committed test the fix must turn green.
  */
def planningInput(request: String, brief: String, kind: Triage.Kind): String =
  val testNote = kind match
    case Triage.Kind.TestableBug(path) =>
      s"\n\nA failing test at `$path` reproduces the bug and is committed on " +
        "this branch. The fix must make it pass without breaking other tests."
    case _ => ""
  s"$request\n\nTriage findings:\n$brief$testNote"

/** The PR body: the generated summary, the issue it closes, and for an
  * untestable bug the steps reviewers can reproduce it with.
  */
def prBody(summary: String, kind: Triage.Kind): String =
  val repro = kind match
    case Triage.Kind.UntestableBug(steps) =>
      s"\n\n## No automated reproduction\n\nNo focused test can show this " +
        s"bug, so none was added. To reproduce it by hand:\n\n$steps"
    case _ => ""
  val closes = issueHandle.fold("")(i => s"\n\nCloses ${i.shortRef}.")
  s"$summary$repro$closes"

flow(orcaArgs, branchNaming = issueHandle.map(BranchNamingStrategy.issue(_))):
  val request = issueHandle.fold(userPrompt): handle =>
    val issue = gh.readIssue(handle)
    s"""Issue ${handle.shortRef}: ${issue.title}
       |Reporter: ${issue.author}
       |
       |${issue.body}""".stripMargin

  val triage = stage("Triage"):
    Plan.autonomous.triage(request, planningAgent).value

  triage match
    case Triage.Reject(reply) =>
      issueHandle match
        case Some(issue) =>
          stage("Comment: rejection"):
            gh.upsertComment(issue, orcaCommentMarker(userPrompt, "reject"), reply)
        case None => display(reply)

    case Triage.Accept(_, brief, kind) =>
      kind match
        case Triage.Kind.TestableBug(testPath) =>
          stage("Reproduce"):
            reproduce(request, testPath)
        case Triage.Kind.UntestableBug(steps) =>
          issueHandle.foreach: issue =>
            stage("Comment: reproduction steps"):
              gh.upsertComment(
                issue,
                orcaCommentMarker(userPrompt, "repro-steps"),
                s"## Reproduction\n\n$steps"
              )
        case Triage.Kind.Change => ()

      val plan = stage("Plan"):
        Plan.autonomous
          .from(planningInput(request, brief, kind), planningAgent)
          .reviewed()
          .value

      val taskOpenFindings =
        for task <- plan.tasks yield
          stage(s"Task: ${task.title}"):
            val session = codingAgent.session("implementer", seed = plan.brief)
            session.run(task.description)
            reviewThenFix(
              coderSession = session,
              reviewers = allReviewers(reviewAgent),
              task = task,
              userRequest = Some(request)
            )

      // Nothing reviews again after this loop, hence the raised fix-turn cap.
      val openFindings = stage("Final review"):
        val finalFixer = codingAgent.session("final-fixer", seed = plan.brief)
        reviewAndFixLoop(
          coderSession = finalFixer,
          reviewers = allReviewers(reviewAgent),
          task = Task(Title("The whole planned change"), plan.brief),
          userRequest = Some(request),
          diff = ReviewDiff.WholeRun,
          maxFixTurns = 5,
          priorOpenFindings = taskOpenFindings.flatMap(_.findings)
        )

      openPrIfGitHub(
        summarisingAgent = codingAgent,
        openFindings = openFindings,
        body = summary => prBody(summary.body, kind),
        context = issueHandle.map(i => s"Originating issue: ${i.shortRef}")
      )
