//> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

/** Simple in-memory planning + coding flow.
  *
  * Mirrors the README example. The agent breaks the user's prompt
  * into a list of tasks (one structured turn), the flow surfaces the
  * plan, and each task is implemented on its own branch with a
  * review-and-fix loop afterwards.
  *
  * Lives alongside the seeded calculator crate so a user can run it
  * from the project's root after `examples/01-simple/create-test-project.sh`:
  *
  * ```bash
  * scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * Requires `claude` logged in and `cargo` on PATH.
  */

import orca.{*, given}
import orca.plan.simple.{Plan, Task}

flow(OrcaArgs(args)):
  // 1. Break the user's prompt into concrete subtasks, interactively.
  val (sessionId, plan) = stage("Creating a development plan"):
    claude.resultAs[Plan].interactive(userPrompt)

  // 2. Implement each task on its own branch and review locally.
  for task <- plan.tasks do
    stage(s"Implement task: ${task.summary}"):
      git.createBranch(task.branchName)
      claude.continueSession(sessionId, task.prompt)
      git.commit(s"Implement ${task.summary}")

      reviewAndFixLoop(
        coder = claude,
        sessionId = sessionId,
        reviewers = defaultReviewers(claude),
        task = task.summary,
        lintCommand = Some("cargo test --quiet")
      )
