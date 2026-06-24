//> using dep "org.virtuslab::orca:0.0.14+28-eb1a8993+20260624-0842-SNAPSHOT"
//> using jvm 21

/** Autonomous planning + coding flow.
  *
  * Mirrors the README example. The agent breaks the prompt into tasks; the
  * task list and implementation progress are tracked in the stage log
  * (`.orca/progress-<hash>.json`), committed on the branch after every stage.
  * A re-run with the same prompt resumes from the first incomplete stage — no
  * plan file, no checkbox state to keep in sync.
  *
  * Each task is implemented on a single feature branch (auto-created from the
  * prompt) with a review-and-fix loop; code + the updated progress entry are
  * committed together. The branch is auto-deleted if no code landed.
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
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, claude).value

  // Get-or-create the implementer session. The seed (plan brief) primes it on
  // first use and is replayed if the backend session is lost on resume.
  val session = claude.session(seed = plan.brief)

  for task <- plan.tasks do
    stage(s"task: ${task.title}"):      // skipped on resume if already done
      claude.runSeeded(task.description, session)
      reviewAndFixLoop(                  // runs under this stage (using InStage)
        coder = claude, sessionId = session,
        reviewers = allReviewers(claude),
        // claude.haiku picks the per-task reviewer subset; swap for
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
      // one commit per task: code + progress entry
