//> using dep "org.virtuslab::orca:0.0.3"
//> using jvm 21

/** Persistent planning + coding flow (autonomous planning).
  *
  * Mirrors the README example. The agent breaks the user's prompt into a list
  * of tasks; the plan is persisted to `.orca/plan-<hash>.md` so a re-run with
  * the same prompt resumes from the first incomplete task. Each task is
  * implemented in sequence on a single epic branch with a review-and-fix loop,
  * the plan's checkbox is ticked, and the work plus the tick are committed
  * together. When every task is done the plan file is removed and the removal
  * is committed.
  *
  * Lives alongside the seeded calculator crate so a user can run it from the
  * project's root after `examples/01-simple/create-test-project.sh`:
  *
  * ```bash
  * scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * Requires `claude` logged in and `cargo` on PATH.
  *
  * For the variant where the planner can ask the user clarifying questions
  * (open-ended prompts, underspecified asks), see `implement-interactive.sc`.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val planFile = Plan.defaultPath(userPrompt)

  // Resume `.orca/plan-<hash>.md` if it exists; otherwise plan + branch.
  // `session` is stable across all tasks — pre-allocated by recoverOrCreate
  // (the planner's session on a fresh run, a new id on recover). Pass it to
  // every implementer/fixer call so they share context.
  val (session, plan) = stage("Acquire plan"):
    Plan.recoverOrCreate(planFile, claude, "orca: starting implementation"):
      Plan.autonomous.from(userPrompt, claude)

  Plan.runPersistent(planFile, plan): task =>
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
        task = task.title.value,
        lintCommand = Some("cargo test --quiet"),
        lintLlm = Some(claude.haiku)
      )
