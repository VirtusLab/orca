//> using dep "org.virtuslab::orca:0.0.12"
//> using jvm 21

/** GitHub-issue → PR flow, fully autonomous.
  *
  * Given a `<owner>/<repo>#<number>` reference (the user's prompt), the flow:
  *
  *   1. Reads the issue from GitHub (title, body, author).
  *   1. Resumes `.orca/plan-<hash>.md` if one exists (crash recovery);
  *      otherwise skeptically assesses the report against the repo — claims,
  *      missing detail, duplicates, scope. The agent returns a plan or a
  *      critique / follow-up question / rebuff.
  *   1. On rejection: posts the agent's reply on the issue and exits.
  *   1. On proceed: creates the epic branch, persists the plan, and runs
  *      `Plan.implementTaskLoop` — each task gets the review-and-fix loop, a
  *      checkbox tick, and a `task: <title>` commit; the plan file is removed
  *      once every task is done.
  *   1. Pushes the branch, folds the diff into a PR title + description via a
  *      cheap model (`summarisePr`), and opens the PR via `gh`.
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

flow(OrcaArgs(args)):

  val issueHandle = IssueHandle.parseOrThrow(userPrompt)

  // 1. Pull the issue from GitHub.
  val issue = stage(s"Read issue ${issueHandle.shortRef}"):
    gh.readIssue(issueHandle)

  // Title + body. Comments are excluded by default — noisy threads pull the
  // planner off-target.
  val issuePayload =
    s"""Issue: ${issue.title}
       |
       |Reporter: ${issue.author}
       |
       |${issue.body}""".stripMargin

  // Resume an in-progress plan for this issue if one exists; otherwise assess
  // (Opus runs read-only to verify claims via Read/Grep). `Verdict.Proceed`
  // persists the plan so a crash resumes from the first incomplete task;
  // `Verdict.Rejection` posts the assessment on the issue and exits.

  // Capture the start branch to return to at the end: Plan.recover /
  // checkoutOrCreate below switch away and stash WIP, so `git stash pop`
  // only lands right if we come back.
  val startBranch = git.currentBranch()

  val planFile = Plan.defaultPath(userPrompt)
  val maybePlan = stage("Acquire plan"):
    Plan
      .recover(planFile)
      .orElse:
        Plan.autonomous.assessThenPlan(issuePayload, claude.opus).value match
          case Verdict.Rejection(_, body) =>
            stage("Post assessment on the issue"):
              gh.writeComment(issueHandle, body)
            None
          case Verdict.Proceed(plan) =>
            git.checkoutOrCreate(plan.epicId)
            os.write.over(planFile, Plan.render(plan), createFolders = true)
            Some(plan)

  maybePlan.foreach: plan =>
    // Fresh session — the assess session was read-only (plan mode). Reused
    // across tasks so the implementer retains context.
    val session = claude.newSession

    Plan.implementTaskLoop(planFile, plan): task =>
      stage(s"Implement task: ${task.title}"):
        stage("Implementation"):
          val _ = claude.autonomous.run(task.description, session)

        reviewAndFixLoop(
          coder = claude,
          sessionId = session,
          reviewers = allReviewers(claude),
          // Haiku picks the per-task reviewer subset; swap for
          // `ReviewerSelector.allEveryRound` to run every reviewer.
          reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
          task = task.title.value,
          // Format after every edit; Prettier for a TS/JS project — swap for
          // your formatter.
          formatCommand = Some("npx prettier --write .")
        )

    stage("Push branch"):
      git.push().orThrow

    val summary = stage("Generate PR title and description"):
      summarisePr(
        llm = claude.haiku,
        // Branch-vs-base diff — `git.diff()` (vs HEAD) would be empty, since
        // `implementTaskLoop` already committed every task.
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
      val _ = gh.createPr(title = summary.title, body = body).orThrow

    // Return to the start branch so any stashed WIP pops back onto it.
    stage(s"Return to $startBranch"):
      git.checkout(startBranch).orThrow
