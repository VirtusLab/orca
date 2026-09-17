// Plan interactively, asking clarifying questions, implement, then open a PR.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.7"
//> using jvm 21

/** Interactive planning + coding flow.
  *
  * Same shape as `implement.sc` — a single review pass per task, then a
  * whole-run review loop — but the planner can drive a conversation: on an
  * underspecified prompt it calls the `ask_user` tool to clarify before
  * producing the plan. A planning stage that already completed is not
  * re-prompted on a re-run.
  *
  * A PR follows when the repository is on GitHub; otherwise the run says so and
  * ends on the feature branch, work committed either way.
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
  * Requires the configured role agents logged in (`claude` by default); `gh` is
  * optional. The seeded calculator example also needs `cargo` on PATH.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val plan = stage("Plan"):
    Plan.interactive.from(userPrompt, planningAgent).value

  // Implementing and fixing are autonomous — `ask_user` was only needed while
  // planning.
  val finalFixer = codingAgent.session(
    "final-fixer",
    detail = "the whole planned change",
    seed = plan.brief
  )

  val taskDeclines =
    for (task, n) <- plan.tasks.zipWithIndex yield
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

  // Nothing reviews again after this loop, hence the raised iteration cap.
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
