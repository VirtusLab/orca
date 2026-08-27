// Plan a prompt into tasks, review each once, then loop a review over the run.
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.1.5"
//> using jvm 21

/** Autonomous planning + coding flow — the README example.
  *
  * The planner breaks the prompt into tasks; each task is implemented on the
  * run's feature branch and reviewed in a single pass. A final stage then loops
  * a review over everything the run changed, checking the per-task fixes with
  * fresh eyes.
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

  val taskDeclines =
    for task <- plan.tasks yield
      stage(s"Task: ${task.title}"):
        session.run(task.description)
        reviewThenFix(
          coderSession = session,
          reviewers = allReviewers(reviewAgent),
          task = task
        )

  // Everything the run changed, reviewed in one loop: each task's single pass
  // took the fixer's word for its own fixes, and this is what checks them. The
  // per-task declines seed the loop, so its reviewers don't re-report findings
  // the fixer already answered.
  stage("Final review"):
    reviewAndFixLoop(
      coderSession = session,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title("The whole planned change"), plan.brief),
      diff = ReviewDiff.WholeRun,
      maxIterations = 3,
      priorDeclines = IgnoredIssues(taskDeclines.flatMap(_.issues))
    )
