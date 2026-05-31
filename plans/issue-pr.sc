//> using dep "org.virtuslab::orca:0.0.7"
//> using jvm 21

/** GitHub-issue → PR flow, fully autonomous.
  *
  * Given a `<owner>/<repo>#<number>` reference (the user's prompt), the flow:
  *
  *   1. Reads the issue from GitHub (title, body, author).
  *   1. Resumes `.orca/plan-<hash>.md` if one exists for this issue (crash
  *      recovery); otherwise skeptically assesses the report against the repo —
  *      verifies claims, looks for missing detail, duplicates, scope problems.
  *      The agent returns either a plan or a critique / follow-up question /
  *      rebuff.
  *   1. On rejection: posts the agent's reply on the issue and exits.
  *   1. On proceed: creates the epic branch, persists the plan, and runs
  *      `Plan.implementTaskLoop` — each task gets the review-and-fix loop, a
  *      checkbox tick on disk, and a `task: <title>` commit; the plan file is
  *      removed and the removal committed once every task is done.
  *   1. Pushes the branch (only PR-bound flows push; `Plan.implementTaskLoop`
  *      itself only commits).
  *   1. Asks a cheap model (`claude.haiku`) via `summarisePr` to fold the diff
  *      into a PR title + description.
  *   1. Opens the PR via `gh`.
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

  // Resume an in-progress plan if one exists for this issue (the planFile
  // path is keyed off `userPrompt = <owner>/<repo>#<number>`). Otherwise
  // assess — Opus runs read-only so it can verify claims via Read/Grep
  // without editing during the assess turn — and on `Verdict.Proceed`
  // persist the plan so a crash resumes from the first incomplete task.
  // `Verdict.Rejection` posts the assessment as an issue comment and the
  // flow exits.
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
    // Fresh implementation session (the assess session was in plan mode
    // and can't write). Reused across tasks so the implementer retains
    // context.
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
          task = task.title.value
        )

    stage("Push branch"):
      git.push().orThrow

    val summary = stage("Generate PR title and description"):
      summarisePr(
        llm = claude.haiku,
        // Branch-vs-default-branch diff — `git.diff()` (vs HEAD) would be
        // empty here since `implementTaskLoop` already committed every task.
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
