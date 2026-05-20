//> using dep "org.virtuslab::orca:0.0.2"
//> using jvm 21

/** Simple in-memory planning + coding flow.
  *
  * Mirrors the README example. The agent breaks the user's prompt into a list
  * of tasks (one structured turn), the flow surfaces the plan, and each task is
  * implemented in sequence on a single epic branch with a review-and-fix loop
  * after each.
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

flow(OrcaArgs(args)):
  // 1. Break the user's prompt into concrete subtasks, interactively.
  // `Plan.interactive.from` opens a conversation the user can drive
  // (clarifying questions, refinements) before producing the plan; swap
  // for `Plan.autonomous.from` for a single agentic turn with no human
  // in the loop. Both wrap the user prompt with `PlanPrompts.Planning`
  // to keep the agent in plan-only mode.
  val (sessionId, plan) = stage("Creating a development plan"):
    Plan.interactive.from(userPrompt, claude)

  // 2. Single branch for the whole epic; tasks become commits on it.
  stage(s"Branch: ${plan.epicId}"):
    git.createBranch(plan.epicId).orThrow

  // 3. Implement each task as a commit on that branch. The review-and-fix
  // loop may modify files in response to reviewer findings, so we commit
  // *after* the loop completes — one commit per task, capturing both the
  // original implementation and any follow-up fixes.
  for task <- plan.tasks do
    stage(s"Implement task: ${task.title}"):
      stage("Implementation"):
        claude.autonomous.continueSession(sessionId, task.description)

      reviewAndFixLoop(
        coder = claude,
        sessionId = sessionId,
        reviewers = allReviewers(claude),
        task = task.title.value,
        lintCommand = Some("cargo test --quiet"),
        lintLlm = Some(claude.haiku)
      )

      git.commit(s"Implement ${task.title}").orThrow
