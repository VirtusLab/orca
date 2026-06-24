//> using dep "org.virtuslab::orca:0.0.14+28-eb1a8993+20260624-0842-SNAPSHOT"
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

flow(orcaArgs, branchNaming = Some(BranchNamingStrategy.issue(issueHandle))):
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
      gh.writeComment(issueHandle, rejectionBody)

  maybePlan.foreach: plan =>
    // Get-or-create the implementer session. Seeded with the brief so the
    // agent has codebase context; replayed on resume if the session is lost.
    val session = claude.session(seed = plan.brief)

    for task <- plan.tasks do
      stage(s"task: ${task.title}"):    // skipped on resume if already done
        claude.runSeeded(task.description, session)
        reviewAndFixLoop(
          coder = claude, sessionId = session,
          reviewers = allReviewers(claude),
          // claude.haiku picks the per-task reviewer subset; swap for
          // `ReviewerSelector.allEveryRound` to run every reviewer.
          reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
          task = task.title.value,
          // Format after every edit; Prettier for a TS/JS project — swap for
          // your formatter.
          formatCommand = Some("npx prettier --write .")
        )
        // one commit per task: code + progress entry

    stage("Push branch"):
      git.push().orThrow

    val summary = stage("Generate PR title and description"):
      summarisePr(
        llm = claude.haiku,
        // Branch-vs-base diff — `git.diff()` (vs HEAD) would be empty, since
        // every task is already committed.
        diff = git.diffVsBase(git.defaultBase()),
        context = Some(
          s"""Originating issue: ${issueHandle.shortRef}
             |Issue title: ${issue.title}""".stripMargin
        )
      )

    stage("Open PR"):
      val body =
        s"""${summary.body}
           |
           |Closes ${issueHandle.shortRef}.""".stripMargin
      gh.createPr(title = summary.title, body = body).orThrow
