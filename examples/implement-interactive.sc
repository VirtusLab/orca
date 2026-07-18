//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.0.17"
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
  * The review loop's format and lint commands come from
  * `.orca/settings.properties`, auto-discovered on first run — the script
  * itself stays stack-agnostic.
  *
  * Requires `claude` logged in and `cargo` on PATH.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val plan = stage("Plan"):
    // `.value` drops the planner's session; the implementer mints its own
    // below. Interactive planning follows the configured planning role too —
    // `ask_user` works on every backend, so no concrete accessor is needed
    // here.
    Plan.interactive.from(userPrompt, planningAgent).value

  // Stable autonomous session on the coding role, shared by implementer and
  // fixer (ask_user was only needed for planning), seeded with the plan
  // brief; primed on first use and replayed if the backend session is lost
  // on resume.
  val session = codingAgent.session("implementer", seed = plan.brief)

  for task <- plan.tasks do
    stage(s"Task: ${task.title}"):      // skipped on resume if already done
      session.run(task.description)
      reviewAndFixLoop(
        coderSession = session,
        reviewers = allReviewers(reviewAgent),
        // reviewerSelection defaults to agentDriven(reviewAgent.cheap); pass
        // `ReviewerSelector.allEveryRound` to run every reviewer instead.
        // Format and lint default to the project's stack settings
        // (`.orca/settings.properties`).
        task = task.title.value
      )
      // one commit per task: code + progress entry
