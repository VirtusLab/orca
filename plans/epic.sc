//> using dep "org.virtuslab::orca:0.0.3"
//> using jvm 21

/** Run an epic: a multi-task workstream with cross-agent review.
  *
  * Two layers stack here:
  *
  *   - **On-disk epic.** `.orca/plan-<hash>.md` holds the task list; on a
  *     fresh run the agent generates it, on a resume the existing file is
  *     recovered (pending edits stashed, branch re-attached) and execution
  *     restarts from the first incomplete task. Each task's `Status: [x]`
  *     checkbox is committed back to the plan file as the task lands, so a
  *     crash mid-flow loses no progress.
  *   - **Cross-agent review.** Claude implements; codex reviews. The
  *     implementing agent is its own worst critic — running reviewers on a
  *     separate model widens coverage without much extra cost. Fixes go back
  *     to the same Claude session. Both CLIs need to be logged in.
  *
  * At the end of a successful run the plan file is removed, then the
  * documentation step updates the project README based on what changed.
  *
  * Lives alongside the seeded todo-cli project so a user can run it from the
  * project's root after `examples/04-epic/create-test-project.sh`:
  *
  * ```bash
  * scala-cli run epic.sc -- \
  *   "Persist tasks to a JSON file at ~/.todo/tasks.json (load on startup, save on every change), \
  *    add 'done <id>' and 'delete <id>' commands, and support priority levels (low/medium/high) \
  *    with a 'list --priority' filter"
  * ```
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val planFile = Plan.defaultPath(userPrompt)

  // 1. Recover from a previous run, or plan from scratch. `recoverOrCreate`
  // stashes pending edits, switches to the plan's branch, and writes the
  // file when no plan exists yet.
  val plan = stage("Acquire epic"):
    Plan.recoverOrCreate(planFile, "orca: starting epic"):
      Plan.autonomous.from(userPrompt, claude.opus)._2

  // 2. Single Claude session across tasks so the agent retains context.
  val (sessionId, _) = claude.autonomous.startSession(
    s"""You are working on the epic at $planFile.
       |
       |The epic defines tasks with short names and prompts. I will
       |send you each task's prompt in turn — implement just that
       |task, commit nothing yourself (the runtime handles commits),
       |and reply briefly when you've finished so I know to move
       |on.""".stripMargin
  )

  // 3. Reviewers run on codex (not claude — the implementing agent
  // is its own worst critic). Claude still drives the fix step,
  // so the same session that implemented the task receives the
  // findings and addresses them in code.
  val reviewers: List[LlmTool[?]] = allReviewers(codex)

  // 4. Iterate. `runPersistent` ticks the checkbox + commits per task,
  // re-reads the plan after each iteration so persisted completions shape the
  // next round, and removes the plan file with a cleanup commit at the end.
  Plan.runPersistent(planFile, plan): task =>
    stage(s"Implement task: ${task.title}"):
      stage("Implementation"):
        val _ = claude.autonomous.continueSession(sessionId, task.description)
      // Format before review so reviewers don't waste turns on
      // style nits the toolchain would fix automatically. Spotless
      // is wired into the seed pom.
      stage("Format"):
        val _ = os
          .proc("mvn", "-q", "spotless:apply")
          .call(cwd = os.pwd, check = false)
      reviewAndFixLoop(
        coder = claude,
        sessionId = sessionId,
        reviewers = reviewers,
        // Haiku picks which codex reviewers run — sees each one's
        // description plus the changed files. Swap for
        // `ReviewerSelector.allEveryRound` to run every reviewer.
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value
      )

  // 5. Documentation pass — update relevant docs based on what
  // changed in this branch.
  stage("Update documentation"):
    val _ = claude.autonomous.continueSession(
      sessionId,
      """All tasks are done. Now update the project's documentation
        |(README, in-code doc-comments where they obviously got
        |stale, etc.) based on the changes you made. Don't invent
        |new docs sections — only update what's affected.""".stripMargin
    )
    git.commit("docs: update for completed work").orThrow
