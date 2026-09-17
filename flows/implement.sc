// Plan a prompt into tasks, review each once, loop a review, then open a PR.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.7"
//> using jvm 21

/** Autonomous planning + coding flow — the README example.
  *
  * The planner breaks the prompt into tasks; each task is implemented on the
  * run's feature branch and reviewed in a single pass. A final stage then loops
  * a review over everything the run changed, checking the per-task fixes with
  * fresh eyes.
  *
  * The run then opens a PR when the repository is on GitHub and hands back the
  * branch it started from — the work is on the PR, its description listing
  * whatever the final review left unfixed. Otherwise it says so in one line
  * and ends on the feature branch; the work is committed either way.
  *
  * `examples/runnable/01-simple/create-test-project.sh` seeds a calculator
  * crate into a temp dir and copies this script alongside it; from there:
  *
  * ```bash
  * scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * Requires the configured role agents logged in (`claude` by default); `gh` is
  * optional. The seeded calculator example also needs `cargo` on PATH.
  *
  * For the variant where the planner can ask clarifying questions, see
  * `implement-interactive.sc`.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, planningAgent).value

  // A session per unit of work — this one for the final review, one per task
  // below: a session spanning the run re-sends every earlier task's transcript
  // on every later API call.
  val finalFixer = codingAgent.session(
    "final-fixer",
    detail = "the whole planned change",
    seed = plan.brief
  )

  val taskDeclines =
    for (task, n) <- plan.tasks.zipWithIndex yield
      // Outside the stage, not in it — a stage body is skipped on resume, and
      // the mint must not be.
      val session = codingAgent.session(
        "implementer",
        detail = s"task ${n + 1}: ${task.title}",
        seed = plan.brief
      )
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
  // the fixer already answered. A higher cap than the library default: nothing
  // reviews again after this loop.
  val openFindings = stage("Final review"):
    reviewAndFixLoop(
      coderSession = finalFixer,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title("The whole planned change"), plan.brief),
      diff = ReviewDiff.WholeRun,
      maxIterations = 5,
      priorDeclines = IgnoredIssues(taskDeclines.flatMap(_.issues))
    )

  openPrIfGitHub(
    summarisingAgent = codingAgent.cheap,
    openFindings = openFindings
  )
