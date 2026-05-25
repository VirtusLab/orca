//> using dep "org.virtuslab::orca:0.0.3"
//> using jvm 21

/** Interactive planning + coding flow.
  *
  * Same shape as `implement.sc` but the planner opens a conversation the user
  * can drive: if the prompt is underspecified, the agent calls the `ask_user`
  * tool to clarify before producing the plan. Each follow-up question shows
  * up in the terminal; typed answers feed straight back to the agent.
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
  // 1. Break the user's prompt into concrete subtasks, interactively.
  // `Plan.interactive.from` lets the planner call `ask_user` whenever the
  // prompt is too vague to plan against — typically one or two questions
  // before the structured `Plan` lands. Swap for `Plan.autonomous.from`
  // (see `implement.sc`) when the prompt is concrete enough that the
  // agent shouldn't need to ask anything.
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
        // Haiku picks which reviewers run per task — sees each one's
        // description plus the changed files. Swap for
        // `ReviewerSelector.allEveryRound` to run every reviewer.
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value,
        lintCommand = Some("cargo test --quiet"),
        lintLlm = Some(claude.haiku)
      )

      git.commit(s"Implement ${task.title}").orThrow
