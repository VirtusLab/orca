// Implement a task directly — no planning stage — review it, then open a PR.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.10"
//> using jvm 21

/** For small, well-scoped changes: the prompt is the only task, with no plan.
  *
  * ```bash
  * scala-cli run --workspace "$(mktemp -d)" quick.sc -- "Add a .gitignore entry for build artifacts"
  * ```
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val openFindings = stage("Implement"):
    // Seeded, so fix turns after a resume still see the task.
    val session = codingAgent.session("implementer", seed = userPrompt)
    session.run("Implement the task from the seed prompt above.")
    reviewAndFixLoop(
      coderSession = session,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title(userPrompt), ""),
      maxFixTurns = 3
    )

  openPrIfGitHub(
    summarisingAgent = codingAgent,
    openFindings = openFindings
  )
