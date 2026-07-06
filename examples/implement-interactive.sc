//> using dep "org.virtuslab::orca:0.0.14+28-eb1a8993+20260624-0842-SNAPSHOT"
//> using jvm 21

/** Interactive planning + coding flow.
  *
  * Same shape as `implement.sc`, but the planner can drive a conversation: on
  * an underspecified prompt it calls the `ask_user` tool to clarify before
  * producing the plan. Progress and resume work identically — the stage log
  * (`.orca/progress-<hash>.json`) is the sole resume mechanism. An interactive
  * planning stage that already completed is replayed from its recorded result
  * without re-prompting on a re-run.
  *
  * `examples/runnable/02-interactive/create-test-project.sh` seeds the
  * calculator crate into a temp dir and copies this script alongside it;
  * from there:
  *
  * ```bash
  * scala-cli run implement-interactive.sc -- "Add a new arithmetic operation to the calculator crate. Ask the user which."
  * ```
  *
  * The trailing "Ask the user which." pushes the planner to call `ask_user`
  * rather than guessing.
  *
  * Requires `claude` logged in and `cargo` on PATH.
  */

import orca.{*, given}

flow(OrcaArgs(args), _.claude):
  val plan = stage("Plan"):
    // `.value` drops the planner's session; the implementer mints its own
    // below (ask_user was only needed for planning).
    Plan.interactive.from(userPrompt, claude).value

  // Stable autonomous session shared by implementer and fixer (ask_user was
  // only needed for planning). The seed primes it on first use and is
  // replayed if the backend session is lost on resume.
  val session = claude.session("implementer", seed = plan.brief)

  for task <- plan.tasks do
    stage(s"task: ${task.title}"):      // skipped on resume if already done
      claude.runSeeded(task.description, session)
      reviewAndFixLoop(
        coder = claude, sessionId = session,
        reviewers = allReviewers(claude),
        // claude.cheap picks the per-task reviewer subset; swap for
        // `ReviewerSelector.allEveryRound` to run every reviewer.
        reviewerSelection = ReviewerSelector.agentDriven(claude.cheap),
        task = task.title.value,
        // Format after every edit so commits stay formatted and reviewers
        // skip style nits.
        formatCommand = Some("cargo fmt"),
        // Cheap sanity gate; correctness is the reviewers' and CI's job, so
        // skip the heavier tests.
        lintCommand = Some("cargo check --tests"),
        lintAgent = Some(claude.cheap)
      )
      // one commit per task: code + progress entry
