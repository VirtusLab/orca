// Plan a prompt into tasks, review each once, loop a review, then open a PR.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.2.0"
//> using jvm 21

/** For a feature or change that needs a plan. Each task gets one review; a
  * final review loop then checks the whole change.
  *
  * To try it on a sample project, run
  * `examples/runnable/01-simple/create-test-project.sh` (needs `cargo`), then:
  *
  * ```bash
  * scala-cli run --workspace "$(mktemp -d)" implement.sc -- "Add a multiply function to the calculator crate"
  * ```
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, planningAgent).value

  val taskOpenFindings =
    for task <- plan.tasks yield
      stage(s"Task: ${task.title}"):
        val session = codingAgent.session("implementer", seed = plan.brief)
        session.run(task.description)
        reviewThenFix(
          coderSession = session,
          reviewers = allReviewers(reviewAgent),
          task = task
        )

  // Nothing reviews after this loop, so it gets more fix turns.
  val openFindings = stage("Final review"):
    val finalFixer = codingAgent.session("final-fixer", seed = plan.brief)
    reviewAndFixLoop(
      coderSession = finalFixer,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title("The whole planned change"), plan.brief),
      diff = ReviewDiff.WholeRun,
      maxFixTurns = 5,
      priorOpenFindings = taskOpenFindings.flatMap(_.findings)
    )

  openPrIfGitHub(
    summarisingAgent = codingAgent,
    openFindings = openFindings
  )
