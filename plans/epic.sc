//> using dep "org.virtuslab::orca:0.0.3"
//> using jvm 21

/** Run an epic: a multi-task workstream with cross-agent review.
  *
  * Two layers are stacked here:
  *
  *   1. **The epic is on disk.** `epic.md` at the working-directory root holds
  *      the task list; on a fresh run the agent generates it, on a resume the
  *      existing file is reused and execution restarts from the first
  *      incomplete task. Each task's `Status: [x]` checkbox is committed back
  *      to `epic.md` as the task lands, so a crash mid-flow loses no progress.
  *
  * 2. **Tasks are reviewed by the *other* backend.** Claude implements; codex
  * reviews. The implementing agent is its own worst critic — running reviewers
  * on a separate model widens coverage without much extra cost. Fixes go back
  * to the same Claude session. Both CLIs need to be logged in.
  *
  * At the end of a successful run the documentation step updates the project
  * README based on what changed, and the epic file is removed (committed as the
  * wrap-up).
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

  val planFile = os.pwd / "epic.md"

  // 1. Epic: generate or reuse. The Step inside `loadOrGenerate`
  // tells the user when an existing file is being reused.
  val plan = stage("Acquiring epic"):
    Plan.autonomous.loadOrGenerate(planFile, userPrompt, claude.opus)

  // 2. Make sure the working tree is clean before we touch it. If
  // it's dirty, stash so the user can recover with `git stash pop`
  // — the Step in the log says exactly that.
  stage("Ensure clean working tree"):
    val _ = git.ensureClean("orca: stashing pre-flow changes")

  // 3. Switch to the plan's branch (creating if needed). Idempotent
  // on resume.
  stage(s"Checkout branch '${plan.epicId}'"):
    git.checkoutOrCreate(plan.epicId)

  // 4. Iterate from the first incomplete task. We open a session
  // once and continue it across tasks so the agent retains context.
  val (sessionId, _) = claude.autonomous.startSession(
    s"""You are working on the epic at $planFile.
       |
       |The epic defines tasks with short names and prompts. I will
       |send you each task's prompt in turn — implement just that
       |task, commit nothing yourself (the runtime handles commits),
       |and reply briefly when you've finished so I know to move
       |on.""".stripMargin
  )

  // Reviewers run on codex (not claude — the implementing agent
  // is its own worst critic). Claude still drives the fix step,
  // so the same session that implemented the task receives the
  // findings and addresses them in code.
  val reviewers: List[LlmTool[?]] = allReviewers(codex)

  // Loop while there's still an incomplete task. We re-read the
  // epic after each task so persisted completion markers shape
  // the next iteration even on resume.
  //
  // Per task: implement → review (may modify files) → mark task
  // complete in epic.md → single commit covering all three. One
  // commit per task keeps the history readable on resume; the
  // checkbox tick lives in the same commit as the work it marks
  // complete, not the next task's commit.
  var currentPlan = plan
  while currentPlan.firstIncomplete.isDefined do
    val task = currentPlan.firstIncomplete.get
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
      Plan.persistComplete(planFile, task.title)
      git.commit(s"task: ${task.title}").orThrow
    currentPlan = Plan.parse(os.read(planFile))

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

  // 6. Wrap-up: remove the epic file so the branch ships cleanly.
  stage("Remove epic file"):
    os.remove(planFile)
    git.commit("chore: remove epic.md").orThrow
