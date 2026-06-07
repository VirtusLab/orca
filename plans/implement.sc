//> using dep "org.virtuslab::orca:0.0.8"
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
  * `examples/01-simple/create-test-project.sh` seeds the calculator crate
  * into a temp directory and copies this script alongside it; run from
  * the seeded directory the seeder prints:
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
  val plan = stage("Acquire plan"):
    Plan.recoverOrCreate(planFile):
      // `.value` drops the planner's read-only session — the implementer
      // below mints a fresh one.
      Plan.autonomous.from(userPrompt, claude).value

  // Stable session reused across every task — implementer and fixer share
  // it so review comments land against the same context that produced the
  // code. Fresh session (not the planner's, which was in plan mode).
  val session = claude.newSession

  Plan.implementTaskLoop(planFile, plan): task =>
    stage(s"Implement task: ${task.title}"):
      stage("Implementation"):
        val _ = claude.autonomous.run(task.description, session)

      // Format before review so reviewers (and the commit) don't burn turns
      // on style nits.
      stage("Format"):
        val _ = os.proc("cargo", "fmt").call(check = false)

      reviewAndFixLoop(
        coder = claude,
        sessionId = session,
        reviewers = allReviewers(claude),
        // Haiku picks the per-task reviewer subset; swap for
        // `ReviewerSelector.allEveryRound` to run every reviewer.
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value,
        // A compile is a cheap sanity gate for the reviewers; correctness is
        // the reviewers' and CI's job, so don't run the (much heavier) tests.
        lintCommand = Some("cargo check --tests"),
        lintLlm = Some(claude.haiku)
      )
