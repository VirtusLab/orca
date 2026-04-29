//> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

/** Simple in-memory planning + coding flow.
  *
  * Mirrors the README example. The agent breaks the user's prompt
  * into a list of tasks (one structured turn), the flow surfaces the
  * plan, and each task is implemented on its own branch with a
  * review-and-fix loop afterwards.
  *
  * Run it from inside a project's working directory:
  *
  * ```bash
  * scala-cli run examples/01-simple/ship.sc -- "Add a multiply method to Calculator"
  * ```
  *
  * The seeded calculator project from `examples/create-test-project.sh`
  * is the easiest target. Requires `claude` logged in.
  */

import orca.{*, given}
import orca.plan.simple.{Plan, Task}

flow(OrcaArgs.from(args.toSeq)):
  // 1. Break the user's prompt into concrete subtasks, interactively.
  val (sessionId, plan) = stage("Creating a development plan"):
    claude.resultAs[Plan].interactive(userPrompt)

  // Surface the plan via the event bus so it shows up in any
  // configured channel — terminal, Slack, HTTP — without the script
  // knowing or caring which.
  plan.logTo

  // 2. Implement each task on its own branch and review locally.
  for task <- plan.tasks do
    stage(s"Implement task: ${task.summary}"):
      git.createBranch(task.branchName)
      claude.continueSession(sessionId, task.prompt)
      git.commit(s"Implement ${task.summary}")

      reviewAndFixLoop(
        coder = claude,
        sessionId = sessionId,
        reviewers = defaultReviewers(claude),
        task = task.summary,
        lintCommand = Some("mvn -q test")
      )
