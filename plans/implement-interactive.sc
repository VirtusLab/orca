//> using dep "org.virtuslab::orca:0.0.3"
//> using jvm 21

/** Interactive planning + coding flow (persistent).
  *
  * Same shape as `implement.sc` but the planner opens a conversation the user
  * can drive: if the prompt is underspecified, the agent calls the `ask_user`
  * tool to clarify before producing the plan. The resulting plan is persisted
  * to `.orca/plan-<hash>.md` so a re-run resumes from the first incomplete
  * task.
  *
  * Lives alongside the seeded calculator crate so a user can run it from the
  * project's root after `examples/02-interactive/create-test-project.sh`:
  *
  * ```bash
  * scala-cli run implement-interactive.sc -- "Add a new arithmetic operation to the calculator crate. Ask the user which."
  * ```
  *
  * The trailing "Ask the user which." pushes the planner to call `ask_user`
  * rather than guessing which operation to add.
  *
  * Requires `claude` logged in and `cargo` on PATH.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val planFile = Plan.defaultPath(userPrompt)

  // Resume `.orca/plan-<hash>.md` if it exists; otherwise plan interactively
  // (the planner can call `ask_user` to clarify) and branch. `session` is
  // stable across all tasks — implementer and fixer share it.
  val (session, plan) = stage("Acquire plan"):
    Plan.recoverOrCreate(planFile, claude, "orca: starting implementation"):
      Plan.interactive.from(userPrompt, claude)

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
