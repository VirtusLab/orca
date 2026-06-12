//> using dep "org.virtuslab::orca:0.0.12"
//> using jvm 21

/** Persistent planning + coding flow (autonomous planning).
  *
  * Mirrors the README example. The agent breaks the prompt into tasks, persisted
  * to `.orca/plan-<hash>.md` so a re-run with the same prompt resumes from the
  * first incomplete task. Each task is implemented on a single epic branch with
  * a review-and-fix loop; the work and the ticked checkbox are committed
  * together. When every task is done the plan file is removed and that removal
  * committed.
  *
  * `examples/runnable/01-simple/create-test-project.sh` seeds the calculator
  * crate into a temp dir and copies this script alongside it; from there:
  *
  * ```bash
  * scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * Requires `claude` logged in and `cargo` on PATH.
  *
  * For the variant where the planner can ask clarifying questions, see
  * `implement-interactive.sc`.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val planFile = Plan.defaultPath(userPrompt)

  // Resume `.orca/plan-<hash>.md` if it exists; otherwise plan + branch.
  val plan = stage("Acquire plan"):
    Plan.recoverOrCreate(planFile):
      // `.value` drops the planner's read-only session; the implementer
      // mints its own.
      Plan.autonomous.from(userPrompt, claude).value

  // Stable session shared by implementer and fixer, so reviews land against
  // the code's own context. Fresh — not the planner's (plan mode).
  val session = claude.newSession

  Plan.implementTaskLoop(planFile, plan): task =>
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
        // Format after every edit so commits stay formatted and reviewers
        // skip style nits.
        formatCommand = Some("cargo fmt"),
        // Cheap sanity gate; correctness is the reviewers' and CI's job, so
        // skip the heavier tests.
        lintCommand = Some("cargo check --tests"),
        lintLlm = Some(claude.haiku)
      )
