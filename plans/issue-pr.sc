//> using dep "org.virtuslab::orca:0.0.3"
//> using jvm 21

/** GitHub-issue → PR flow, fully autonomous.
  *
  * Given a `<owner>/<repo>#<number>` reference (the user's prompt), the flow:
  *
  *   1. Reads the issue from GitHub (title, body, author).
  *   2. Skeptically assesses the report against the repo — verifies claims,
  *      looks for missing detail, duplicates, scope problems. The agent
  *      returns either a plan to implement or a critique / follow-up
  *      question / rebuff.
  *   3. On rejection: posts the agent's reply on the issue and exits cleanly,
  *      no PR.
  *   4. On proceed: creates the epic branch and implements each task with the
  *      review-and-fix loop, committing per task.
  *   5. Pushes the branch.
  *   6. Asks a cheap model (`claude.haiku`) to summarise the diff into a PR
  *      title + description.
  *   7. Opens the PR via `gh`.
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

/** Output shape for the cheap-model PR write-up turn. Top-level so the `derives
  * JsonData` macro evaluates outside a method body.
  */
case class PrSummary(title: String, body: String) derives JsonData

flow(OrcaArgs(args)):

  // Parse `<owner>/<repo>#<number>` from the user's prompt.
  val IssuePattern = """\s*([^/\s]+)/([^#\s]+)#(\d+)\s*""".r
  val issueHandle = userPrompt match
    case IssuePattern(owner, repo, number) =>
      IssueHandle(owner = owner, repo = repo, number = number.toInt)
    case _ =>
      fail(
        "expected userPrompt of the form '<owner>/<repo>#<number>', " +
          s"got: '$userPrompt'"
      )

  // 1. Pull the issue from GitHub.
  val issue = stage(
    s"Read issue ${issueHandle.owner}/" +
      s"${issueHandle.repo}#${issueHandle.number}"
  ):
    gh.readIssue(issueHandle)

  // Title + body. Comments are excluded by default — noisy threads pull the
  // planner off-target.
  val issuePayload =
    s"""Issue: ${issue.title}
       |
       |Reporter: ${issue.author}
       |
       |${issue.body}""".stripMargin

  // Opus runs read-only so it can verify claims via Read/Grep without
  // editing during the assess turn.
  val verdict = stage("Assess and plan"):
    Plan.autonomous.assessThenPlan(issuePayload, claude.opus)

  // Pattern-match rather than `return` — `flow` is a lambda body.
  verdict match
    case Verdict.Rejection(_, body) =>
      stage("Post assessment on the issue"):
        gh.writeComment(issueHandle, body)

    case Verdict.Proceed(plan) =>
      stage(s"Branch: ${plan.epicId}"):
        git.createBranch(plan.epicId).orThrow

      // Fresh implementation session (the assess session was in plan mode
      // and can't write). Lazily started by the first task; reused thereafter.
      var session: Option[SessionId[BackendTag.ClaudeCode.type]] = None

      for task <- plan.tasks do
        stage(s"Implement task: ${task.title}"):
          val sid = stage("Implementation"):
            val (next, _) = claude.autonomous.run(task.description, resume = session)
            session = Some(next)
            next

          reviewAndFixLoop(
            coder = claude,
            sessionId = sid,
            reviewers = allReviewers(claude),
            // Haiku picks the per-task reviewer subset; swap for
            // `ReviewerSelector.allEveryRound` to run every reviewer.
            reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
            task = task.title.value
          )

          git.commit(s"Implement ${task.title}").orThrow

      stage("Push branch"):
        git.push().orThrow

      // Haiku summarises the diff — cheap model, summarisation task.
      val summary = stage("Draft PR title and description"):
        val diff = git.diff()
        val (_, s) = claude.haiku
          .resultAs[PrSummary]
          .autonomous
          .run(
            s"""Summarise the following work into a PR title and a PR
               |description body. The title should fit on one line; the
               |body should be a few short paragraphs covering what
               |changed and why, anchored to the originating issue.
               |
               |Originating issue: ${issueHandle.owner}/${issueHandle.repo}#${issueHandle.number}
               |Issue title: ${issue.title}
               |
               |Branch diff (vs base):
               |
               |```diff
               |$diff
               |```""".stripMargin
          )
        s

      stage("Open PR"):
        val body =
          s"""${summary.body}
             |
             |Closes ${issueHandle.owner}/${issueHandle.repo}#${issueHandle.number}.""".stripMargin
        val _ = gh.createPr(title = summary.title, body = body).orThrow
