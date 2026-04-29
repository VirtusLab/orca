//> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

/** Extended planning flow with a markdown-backed, resumable plan.
  *
  * The plan lives in `dev.md` at the root of the working directory.
  * On a fresh run the agent generates it; on a resume the existing
  * file is reused and execution restarts from the first incomplete
  * task. Each task's `Status: [x]` checkbox is committed back to
  * `dev.md` after the task lands, so a crash mid-flow doesn't lose
  * progress.
  *
  * At the end of a successful run the documentation step updates
  * the project README based on what changed, and the plan file is
  * removed (committed as the wrap-up).
  *
  * Run from the project's working directory:
  *
  * ```bash
  * scala-cli run <orca-sandbox>/examples/03-extended-planning/dev.sc -- \
  *   "Add a divide method to Calculator with full test coverage"
  * ```
  */

import orca.{*, given}
import orca.plan.extended.Plan

flow(OrcaArgs.from(args.toSeq)):

  val planFile = os.pwd / "dev.md"

  // 1. Plan: generate or reuse. The Step inside `loadOrGenerate`
  // tells the user when an existing file is being reused.
  val plan = stage("Acquiring plan"):
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
    s"""You are working on the development plan at $planFile.
       |
       |The plan defines tasks with short names and prompts. I will
       |send you each task's prompt in turn — implement just that
       |task, commit nothing yourself (the runtime handles commits),
       |and reply briefly when you've finished so I know to move
       |on.""".stripMargin
  )

  // Loop while there's still an incomplete task. We re-read the
  // plan after each task so persisted completion markers shape
  // the next iteration even on resume.
  var currentPlan = plan
  while currentPlan.firstIncomplete.isDefined do
    val task = currentPlan.firstIncomplete.get
    stage(s"Implement task: ${task.name}"):
      val _ = claude.continueSession(sessionId, task.prompt)
      git.commit(s"task: ${task.name}")
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

  // 6. Wrap-up: remove the plan file so the branch ships cleanly.
  stage("Remove plan file"):
    os.remove(planFile)
    git.commit("chore: remove dev.md plan file")
