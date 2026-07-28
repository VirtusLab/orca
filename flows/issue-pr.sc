// GitHub issue (owner/repo#N) → assess, plan, implement, PR — or reject.
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.1.0"
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
  * Given a `<owner>/<repo>#<number>` reference (the user's prompt), the flow:
  *
  *   1. Reads the issue from GitHub.
  *   1. Skeptically assesses the report against the repo (claims, missing
  *      detail, duplicates, scope) and either proceeds with a plan or rejects.
  *   1. On rejection: posts the agent's reply on the issue.
  *   1. On proceed: runs the per-task implement + review-and-fix loop.
  *   1. Pushes the branch and opens a PR with a cheap-model-generated title
  *      and description.
  *
  * The feature branch is named deterministically from the issue number
  * (`fix/issue-<n>`), so a re-run after a crash lands on the same branch.
  *
  * Usage — pass `<owner>/<repo>#<number>`:
  *
  * ```bash
  * scala-cli run issue-pr.sc -- "acme/widgets#42"
  * ```
  *
  * Requires `gh` authenticated, and the configured role agents logged in
  * (`claude` by default).
  */

import orca.{*, given}

// Parse the issue handle up-front so it can seed the deterministic branch
// naming strategy passed to `flow`. A parse failure exits before the flow.
val orcaArgs = OrcaArgs(args)
val issueHandle = IssueHandle.parseOrThrow(orcaArgs.userPrompt)

// Opens a PR, so return to the starting branch afterward.
flow(
  orcaArgs,
  branchNaming = Some(BranchNamingStrategy.issue(issueHandle)),
  returnToStartBranch = true
):
  val issue = gh.readIssue(issueHandle)

  val issuePayload =
    s"""Issue: ${issue.title}
       |
       |Reporter: ${issue.author}
       |
       |${issue.body}""".stripMargin

  // Stage returns (plan, rejectionBody): exactly one of (Some(plan), "") or
  // (None, body). Splitting the verdict and the comment into two stages means
  // a crash between them doesn't double-post the comment on resume.
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
    val session = codingAgent.session("implementer", seed = plan.brief)

    for task <- plan.tasks do
      stage(s"Task: ${task.title}"):
        session.run(task.description)
        reviewAndFixLoop(
          coderSession = session,
          reviewers = allReviewers(reviewAgent),
          task = task.title.value
        )

    openPrFromBranch(
      summarisingAgent = codingAgent.cheap,
      body = summary =>
        s"""${summary.body}
           |
           |Closes ${issueHandle.shortRef}.""".stripMargin,
      context = Some(
        s"""Originating issue: ${issueHandle.shortRef}
           |Issue title: ${issue.title}""".stripMargin
      )
    )
