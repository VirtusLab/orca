//> using dep "org.virtuslab::orca:0.0.9"
//> using jvm 21

/** Interactive planning + coding flow (persistent).
  *
  * Same shape as `implement.sc` but the planner opens a conversation the user
  * can drive: if the prompt is underspecified, the agent calls the `ask_user`
  * tool to clarify before producing the plan. The resulting plan is persisted
  * to `.orca/plan-<hash>.md` so a re-run resumes from the first incomplete
  * task.
  *
  * `examples/runnable/02-interactive/create-test-project.sh` seeds the calculator
  * crate into a temp directory and copies this script alongside it; run
  * from the seeded directory the seeder prints:
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
  // (the planner can call `ask_user` to clarify) and branch.
  val plan = stage("Acquire plan"):
    Plan.recoverOrCreate(planFile):
      // `.value` drops the planner's session — the implementer below mints a
      // fresh one (ask_user was only needed for planning).
      Plan.interactive.from(userPrompt, claude).value

  // Stable autonomous session reused across every task — ask_user was only
  // needed for planning. Implementer and fixer share it.
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
        // Format after every edit (the implementation and each review fix), so
        // the committed code stays formatted and reviewers skip style nits.
        formatCommand = Some("cargo fmt"),
        // A compile is a cheap sanity gate for the reviewers; correctness is
        // the reviewers' and CI's job, so don't run the (much heavier) tests.
        lintCommand = Some("cargo check --tests"),
        lintLlm = Some(claude.haiku)
      )
