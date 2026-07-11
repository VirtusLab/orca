//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.0.14"
//> using jvm 21

/** GitHub-issue → PR flow, fully autonomous.
  *
  * Given a `<owner>/<repo>#<number>` reference (the user's prompt), the flow:
  *
  *   1. Reads the issue from GitHub (title, body, author) — outside any stage,
  *      since it is a pure read.
  *   1. Skeptically assesses the report against the repo (claims, missing
  *      detail, duplicates, scope) and either proceeds with a plan or rejects.
  *   1. On rejection: posts the agent's reply on the issue. The throwaway
  *      branch (no code committed) is auto-deleted by the runtime on exit.
  *   1. On proceed: runs the per-task implement + review-and-fix loop. The
  *      task list and progress live in the stage log; a re-run resumes from
  *      the first incomplete stage.
  *   1. Pushes the branch, folds the diff into a PR title + description via
  *      haiku (`summarisePr`), and opens the PR via `gh` (idempotent by branch).
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
  * Requires `claude` and `gh` both authenticated.
  */

import orca.{*, given}

// Parse the issue handle up-front so it can seed the deterministic branch
// naming strategy passed to `flow`. A parse failure exits before the flow.
val orcaArgs = OrcaArgs(args)
val issueHandle = IssueHandle.parseOrThrow(orcaArgs.userPrompt)

// returnToStartBranch: this flow opens a PR, so switch back to the starting
// branch afterward (ready for the next task) rather than staying on the feature
// branch — which is the default for no-PR flows like implement.sc.
flow(
  orcaArgs,
  _.claude,
  branchNaming = Some(BranchNamingStrategy.issue(issueHandle)),
  returnToStartBranch = true
):
  // Pure read — outside any stage (reads don't need InStage).
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
    Plan.autonomous.assessThenPlan(issuePayload, claude.opus).value match
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
    // Get-or-create the implementer session, seeded with the plan brief
    // (replayed on resume if the session is lost).
    val session = claude.session("implementer", seed = plan.brief)

    for task <- plan.tasks do
      stage(s"Task: ${task.title}"):    // skipped on resume if already done
        session.run(task.description)
        // reviewerSelection defaults to agentDriven(claude.cheap); pass
        // `ReviewerSelector.allEveryRound` to run every reviewer instead.
        reviewAndFixLoop(
          coderSession = session,
          reviewers = allReviewers(claude),
          task = task.title.value,
          // Format after every edit; Prettier for a TS/JS project — swap for
          // your formatter.
          formatCommand = Some("npx prettier --write .")
        )
        // one commit per task: code + progress entry

    // Push → summarise → create, as three resume-safe stages. The summariser
    // sees the branch-vs-base diff (git.diff() vs HEAD would be empty here,
    // since every task is already committed); the body appends the issue closer.
    openPrFromBranch(
      summarisingAgent = claude.cheap,
      body = summary =>
        s"""${summary.body}
           |
           |Closes ${issueHandle.shortRef}.""".stripMargin,
      context = Some(
        s"""Originating issue: ${issueHandle.shortRef}
           |Issue title: ${issue.title}""".stripMargin
      )
    )
