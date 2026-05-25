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

  // 1. Acquire the plan: resume from a previous run if `.orca/plan-<hash>.md`
  // exists (recover stashes pending edits + switches to the plan's branch);
  // otherwise generate one autonomously, stash, switch to its branch, and
  // write the file.
  val plan = stage("Acquire plan"):
    Plan.recoverOrCreate(planFile, "orca: starting implementation"):
      Plan.autonomous.from(userPrompt, claude)._2

  // 2. Single session across all tasks so the agent retains context. Started
  // here (rather than inside `runPersistent`) so a resume picks up with a
  // fresh thread that still sees the persisted plan as its source of truth.
  val (sessionId, _) = claude.autonomous.startSession(
    s"""You are working on the plan at $planFile. Each task is sent to
       |you in turn; implement only that task and reply briefly when
       |you're done so I know to move on.""".stripMargin
  )

  // 3. Iterate. `runPersistent` ticks the checkbox + commits per task and
  // removes the plan file once everything is done.
  Plan.runPersistent(planFile, plan): task =>
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
