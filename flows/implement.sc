// Plan a prompt into tasks and implement each with a review-and-fix loop.
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.1.4"
//> using jvm 21

/** Autonomous planning + coding flow — the README example.
  *
  * The planner breaks the prompt into tasks; each task is implemented on the
  * run's feature branch and taken through a review-and-fix loop.
  *
  * `examples/runnable/01-simple/create-test-project.sh` seeds a calculator
  * crate into a temp dir and copies this script alongside it; from there:
  *
  * ```bash
  * scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * Requires the configured role agents logged in (`claude` by default); the
  * seeded calculator example also needs `cargo` on PATH.
  *
  * For the variant where the planner can ask clarifying questions, see
  * `implement-interactive.sc`.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, planningAgent).value

  val session = codingAgent.session("implementer", seed = plan.brief)

  for task <- plan.tasks do
    stage(s"Task: ${task.title}"):
      session.run(task.description)
      reviewAndFixLoop(
        coderSession = session,
        reviewers = allReviewers(reviewAgent),
        task = task,
        maxIterations = 3
      )
