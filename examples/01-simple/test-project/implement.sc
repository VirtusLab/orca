//> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

/** Simple in-memory planning + coding flow.
  *
  * Mirrors the README example. The agent breaks the user's prompt into a list
  * of tasks (one structured turn), the flow surfaces the plan, and each task
  * is implemented in sequence on a single epic branch with a review-and-fix
  * loop after each.
  *
  * Lives alongside the seeded calculator crate so a user can run it from the
  * project's root after `examples/01-simple/create-test-project.sh`:
  *
  * ```bash
  * scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * Requires `claude` logged in and `cargo` on PATH.
  */

import orca.{*, given}
import orca.plan.SimplePlan
import orca.review.{defaultReviewers, reviewAndFixLoop}

flow(OrcaArgs(args)):
  // 1. Break the user's prompt into concrete subtasks, interactively.
  val (sessionId, plan) = stage("Creating a development plan"):
    claude.resultAs[SimplePlan].interactive(userPrompt)

  // 2. Single branch for the whole epic; tasks become commits on it.
  stage(s"Branch: ${plan.epicId}"):
    git.createBranch(plan.epicId)

  // 3. Implement each task as a commit on that branch. The review-and-fix
  // loop may modify files in response to reviewer findings, so we commit
  // *after* the loop completes — one commit per task, capturing both the
  // original implementation and any follow-up fixes.
  for task <- plan.tasks do
    stage(s"Implement task: ${task.title}"):
      claude.continueSession(sessionId, task.description)

      // Run the project's formatter before review so reviewers don't
      // waste turns on whitespace nits the toolchain would fix
      // automatically. `check = false` so an absent toolchain doesn't
      // abort the flow — the lint step would catch it.
      stage("Format"):
        val _ = os.proc("cargo", "fmt").call(cwd = os.pwd, check = false)

      reviewAndFixLoop(
        coder = claude,
        sessionId = sessionId,
        reviewers = defaultReviewers(claude),
        task = task.title.value,
        lintCommand = Some("cargo test --quiet")
      )

      git.commit(s"Implement ${task.title}")
