// GitHub issue (owner/repo#N or URL) → assess, plan, implement, PR — or reject.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.7"
//> using jvm 21

/** GitHub-issue → PR flow, fully autonomous.
  *
  * Takes any issue — a feature request, a change, a bug — and goes straight
  * from assessment to a plan. For a bug report where a reproduction should come
  * first, `issue-pr-bugfix.sc` writes a CI-verified failing test before fixing.
  *
  * Two outcomes, decided by the assessment: a comment on the issue explaining
  * why it was rejected, or a PR implementing it. Only the second writes any
  * code.
  *
  * Given a `<owner>/<repo>#<number>` reference to an issue, or its GitHub URL,
  * the flow:
  *
  *   1. Reads the issue from GitHub.
  *   1. Skeptically assesses the report against the repo (claims, missing
  *      detail, duplicates, scope) and either proceeds with a plan or rejects.
  *   1. On rejection: posts the agent's reply on the issue.
  *   1. On proceed: implements each task and reviews it in a single pass, then
  *      loops a review over everything the run changed.
  *   1. Pushes the branch and opens a PR with a cheap-model-generated title
  *      and description, plus a section listing whatever the final review
  *      left unfixed.
  *
  * The feature branch is named deterministically from the issue number
  * (`fix/issue-<n>`), so a re-run after a crash lands on the same branch.
  *
  * Usage — pass `<owner>/<repo>#<number>` or the issue's github.com URL (no
  * query string or `#...` anchor):
  *
  * ```bash
  * scala-cli run issue-pr.sc -- "acme/widgets#42"
  * scala-cli run issue-pr.sc -- "https://github.com/acme/widgets/issues/42"
  * ```
  *
  * Use the same form on re-runs: the progress log and the issue-comment marker
  * are keyed on the prompt text, so switching between the two starts a fresh
  * run and can post a duplicate comment.
  *
  * Requires `gh` authenticated, and the configured role agents logged in
  * (`claude` by default).
  */

import orca.{*, given}

// Parsed before `flow` so it can seed the deterministic branch naming; a parse
// failure exits before the run starts.
val orcaArgs = OrcaArgs(args)
val issueHandle = IssueHandle.parseOrThrow(orcaArgs.userPrompt)

flow(
  orcaArgs,
  branchNaming = Some(BranchNamingStrategy.issue(issueHandle))
):
  val issue = gh.readIssue(issueHandle)

  val issuePayload =
    s"""Issue: ${issue.title}
       |
       |Reporter: ${issue.author}
       |
       |${issue.body}""".stripMargin

  // Verdict and comment are separate stages, so a crash between them doesn't
  // double-post the comment on resume.
  val (maybePlan, rejectionBody) = stage("Assess and plan"):
    Plan.autonomous.assessThenPlan(issuePayload, planningAgent).value match
      case Verdict.Rejection(_, body) => (None: Option[Plan], body)
      case Verdict.Proceed(plan)      => (Some(plan), "")

  if maybePlan.isEmpty then
    stage("Comment: rejection"):
      gh.upsertComment(
        issueHandle,
        orcaCommentMarker(userPrompt, "reject"),
        rejectionBody
      )

  maybePlan.foreach: plan =>
    val finalFixer = codingAgent.session(
      "final-fixer",
      detail = "the whole planned change",
      seed = plan.brief
    )

    val taskDeclines =
      for (task, n) <- plan.tasks.zipWithIndex yield
        val session = codingAgent.session(
          "implementer",
          detail = s"task ${n + 1}: ${task.title}",
          seed = plan.brief
        )
        stage(s"Task: ${task.title}"):
          session.run(task.description)
          reviewThenFix(
            coderSession = session,
            reviewers = allReviewers(reviewAgent),
            task = task,
            userRequest = Some(issuePayload)
          )

    // Nothing reviews again after this loop, hence the raised iteration cap.
    val openFindings = stage("Final review"):
      reviewAndFixLoop(
        coderSession = finalFixer,
        reviewers = allReviewers(reviewAgent),
        task = Task(Title("The whole planned change"), plan.brief),
        userRequest = Some(issuePayload),
        diff = ReviewDiff.WholeRun,
        maxIterations = 5,
        priorDeclines = IgnoredIssues(taskDeclines.flatMap(_.issues))
      )

    openPrFromBranch(
      summarisingAgent = codingAgent.cheap,
      openFindings = openFindings,
      body = summary =>
        s"""${summary.body}
           |
           |Closes ${issueHandle.shortRef}.""".stripMargin,
      context = Some(
        s"""Originating issue: ${issueHandle.shortRef}
           |Issue title: ${issue.title}""".stripMargin
      )
    )
