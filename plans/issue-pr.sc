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

  // The full payload to plan against — title + body. We don't include
  // comments by default; the issue body is usually self-contained, and
  // including a noisy comment thread can pull the planner off-target.
  val issuePayload =
    s"""Issue: ${issue.title}
       |
       |Reporter: ${issue.author}
       |
       |${issue.body}""".stripMargin

  // 2. Assess the report and either plan or reject. The opus model gets full
  // tool access so it can verify claims against the repo (read files, run
  // searches) before committing to either branch.
  val (sessionId, verdict) = stage("Assess and plan"):
    Plan.autonomous.assessThenPlan(issuePayload, claude.opus)

  // Branching here rather than `return` — `flow` is a lambda body, and
  // Scala 3 doesn't allow non-local returns from lambdas.
  verdict match
    case Verdict.Rejection(_, body) =>
      // 3. On rejection: surface the agent's reply on the issue and stop.
      stage("Post assessment on the issue"):
        gh.writeComment(issueHandle, body)

    case Verdict.Proceed(plan) =>
      // 4. Branch for the whole epic, then implement each task on that
      // branch with the review-and-fix loop. One commit per task.
      stage(s"Branch: ${plan.epicId}"):
        git.createBranch(plan.epicId).orThrow

      for task <- plan.tasks do
        stage(s"Implement task: ${task.title}"):
          stage("Implementation"):
            claude.autonomous.continueSession(sessionId, task.description)

          reviewAndFixLoop(
            coder = claude,
            sessionId = sessionId,
            reviewers = allReviewers(claude),
            // Haiku picks which reviewers run — sees each one's description
            // plus the changed files. Swap for `ReviewerSelector.allEveryRound`
            // to run every reviewer.
            reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
            task = task.title.value
          )

          git.commit(s"Implement ${task.title}").orThrow

      // 5. Push the branch so a PR can be opened against it.
      stage("Push branch"):
        git.push().orThrow

      // 6. Generate PR title + description from the diff with a cheap
      // model. The planning + implementation agent already did the heavy
      // thinking; the PR write-up is a summarisation task that haiku
      // handles fine and saves tokens.
      val summary = stage("Draft PR title and description"):
        val diff = git.diff()
        claude.haiku
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

      // 7. Open the PR.
      stage("Open PR"):
        val body =
          s"""${summary.body}
             |
             |Closes ${issueHandle.owner}/${issueHandle.repo}#${issueHandle.number}.""".stripMargin
        val pr = gh.createPr(title = summary.title, body = body).orThrow
        val _ = pr
