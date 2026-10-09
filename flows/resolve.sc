// Resolve a request or GitHub issue: triage, reproduce a bug, fix or build it, PR.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.2.0"
//> using jvm 21

/** The prompt is a bug report, a feature request, or a GitHub issue
  * (`<owner>/<repo>#<number>` or its URL; needs `gh`). For an issue, the branch
  * is `fix/issue-<n>` and replies are posted on the issue.
  *
  * Triage rejects the request with a reply, or accepts it as:
  *
  *   - a bug a test can show: a failing test is written first;
  *   - a bug no test can show: fixed, and the PR lists manual reproduction
  *     steps;
  *   - a change: planned and built directly.
  *
  * ```bash
  * scala-cli run --workspace "$(mktemp -d)" resolve.sc -- "acme/widgets#42"
  * scala-cli run --workspace "$(mktemp -d)" resolve.sc -- "Dividing by zero crashes the calculator"
  * ```
  */

import orca.{*, given}

val orcaArgs = OrcaArgs(args)

/** The issue the prompt names, when the whole prompt is an issue reference. */
val issueHandle: Option[IssueHandle] =
  IssueHandle.parseIssue(orcaArgs.userPrompt).toOption

def planningInput(request: String, brief: String, kind: Triage.Kind): String =
  val testNote = kind match
    case Triage.Kind.TestableBug(path) =>
      s"\n\nA failing test at `$path` reproduces the bug and is committed on " +
        "this branch. The fix must make it pass without breaking other tests."
    case Triage.Kind.UntestableBug(_) | Triage.Kind.Change => ""
  s"$request\n\nTriage findings:\n$brief$testNote"

def prBody(summary: String, kind: Triage.Kind): String =
  val repro = kind match
    case Triage.Kind.UntestableBug(steps) =>
      s"\n\n## No automated reproduction\n\nNo focused test can show this " +
        s"bug, so none was added. To reproduce it by hand:\n\n$steps"
    case Triage.Kind.TestableBug(_) | Triage.Kind.Change => ""
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
            reproduceBug(
              request = request,
              testPath = testPath,
              agent = codingAgent
            )
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
          .from(
            planningInput(request = request, brief = brief, kind = kind),
            planningAgent
          )
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

      // Nothing reviews after this loop, so it gets more fix turns.
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
