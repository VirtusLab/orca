//> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

/** Run an epic: a multi-task workstream with cross-agent review.
  *
  * Two layers are stacked here:
  *
  * 1. **The epic is on disk.** `epic.md` at the working-directory
  *    root holds the task list; on a fresh run the agent
  *    generates it, on a resume the existing file is reused and
  *    execution restarts from the first incomplete task. Each
  *    task's `Status: [x]` checkbox is committed back to `epic.md`
  *    as the task lands, so a crash mid-flow loses no progress.
  *
  * 2. **Each task is reviewed by both backends.** After Claude
  *    implements a task, `defaultReviewers(claude)` *and*
  *    `defaultReviewers(codex)` run in parallel against the same
  *    diff. Disagreement is the point — when one backend misses a
  *    bug the other catches, the task doesn't ship until the
  *    agent has fixed it. This needs both `claude` and `codex`
  *    CLIs logged in.
  *
  * At the end of a successful run the documentation step updates
  * the project README based on what changed, and the epic file is
  * removed (committed as the wrap-up).
  *
  * Lives alongside the seeded todo-cli project so a user can run
  * it from the project's root after
  * `examples/03-epic/create-test-project.sh`:
  *
  * ```bash
  * scala-cli run epic.sc -- \
  *   "Persist tasks to a JSON file at ~/.todo/tasks.json (load on startup, save on every change), \
  *    add 'done <id>' and 'delete <id>' commands, and support priority levels (low/medium/high) \
  *    with a 'list --priority' filter"
  * ```
  */

import orca.{*, given}
import orca.plan.extended.Plan

flow(OrcaArgs(args)):

  val planFile = os.pwd / "epic.md"

  // 1. Epic: generate or reuse. The Step inside `loadOrGenerate`
  // tells the user when an existing file is being reused.
  val plan = stage("Acquiring epic"):
    Plan.loadOrGenerate(planFile, userPrompt, claude.opus)

  // 2. Make sure the working tree is clean before we touch it. If
  // it's dirty, stash so the user can recover with `git stash pop`
  // — the Step in the log says exactly that.
  stage("Ensure clean working tree"):
    val _ = git.ensureClean("orca: stashing pre-flow changes")

  // 3. Switch to the plan's branch (creating if needed). Idempotent
  // on resume.
  stage(s"Checkout branch '${plan.branchName}'"):
    git.checkoutOrCreate(plan.branchName)

  // 4. Iterate from the first incomplete task. We open a session
  // once and continue it across tasks so the agent retains context.
  val (sessionId, _) = claude.startSession(
    s"""You are working on the epic at $planFile.
       |
       |The epic defines tasks with short names and prompts. I will
       |send you each task's prompt in turn — implement just that
       |task, commit nothing yourself (the runtime handles commits),
       |and reply briefly when you've finished so I know to move
       |on.""".stripMargin
  )

  // Cross-agent reviewer set: each canonical reviewer dimension
  // (performance, readability, test coverage, code functionality,
  // abstraction) runs once on Claude and once on Codex, all in
  // parallel inside `reviewAndFixLoop`. Fixes go back through the
  // same Claude session that did the implementation.
  val reviewers: List[LlmTool[?]] =
    defaultReviewers(claude) ++ defaultReviewers(codex)

  // Loop while there's still an incomplete task. We re-read the
  // epic after each task so persisted completion markers shape
  // the next iteration even on resume.
  var currentPlan = plan
  while currentPlan.firstIncomplete.isDefined do
    val task = currentPlan.firstIncomplete.get
    stage(s"Implement task: ${task.name}"):
      val _ = claude.continueSession(sessionId, task.prompt)
      git.commit(s"task: ${task.name}")
      reviewAndFixLoop(
        coder = claude,
        sessionId = sessionId,
        reviewers = reviewers,
        task = task.name
      )
      Plan.persistComplete(planFile, task.name)
    currentPlan = Plan.parse(os.read(planFile))

  // 5. Documentation pass — update relevant docs based on what
  // changed in this branch.
  stage("Update documentation"):
    val _ = claude.continueSession(
      sessionId,
      """All tasks are done. Now update the project's documentation
        |(README, in-code doc-comments where they obviously got
        |stale, etc.) based on the changes you made. Don't invent
        |new docs sections — only update what's affected.""".stripMargin
    )
    git.commit("docs: update for completed work")

  // 6. Wrap-up: remove the epic file so the branch ships cleanly.
  stage("Remove epic file"):
    os.remove(planFile)
    git.commit("chore: remove epic.md")
