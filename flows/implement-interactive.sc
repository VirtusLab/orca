// Plan interactively, asking clarifying questions, then implement the tasks.
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.1.4"
//> using jvm 21

/** Interactive planning + coding flow.
  *
  * Same shape as `implement.sc` — a single review pass per task, then a
  * whole-run review loop — but the planner can drive a conversation: on
  * an underspecified prompt it calls the `ask_user` tool to clarify before
  * producing the plan. A planning stage that already completed is not
  * re-prompted on a re-run.
  *
  * `examples/runnable/02-interactive/create-test-project.sh` seeds a calculator
  * crate into a temp dir and copies this script alongside it; from there:
  *
  * ```bash
  * scala-cli run implement-interactive.sc -- "Add a new arithmetic operation to the calculator crate. Ask the user which."
  * ```
  *
  * The trailing "Ask the user which." pushes the planner to call `ask_user`
  * rather than guessing.
  *
  * Requires the configured role agents logged in (`claude` by default); the
  * seeded calculator example also needs `cargo` on PATH.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val plan = stage("Plan"):
    Plan.interactive.from(userPrompt, planningAgent).value

  // One autonomous session for implementing and fixing alike — ask_user was
  // only needed while planning.
  val session = codingAgent.session("implementer", seed = plan.brief)

  for task <- plan.tasks do
    stage(s"Task: ${task.title}"):
      session.run(task.description)
      reviewThenFix(
        coderSession = session,
        reviewers = allReviewers(reviewAgent),
        task = task
      )

  // Everything the run changed, reviewed in one loop: each task's single pass
  // took the fixer's word for its own fixes, and this is what checks them.
  stage("Final review"):
    reviewAndFixLoop(
      coderSession = session,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title("The whole planned change"), plan.brief),
      diff = ReviewDiff.WholeRun,
      maxIterations = 5
    )
