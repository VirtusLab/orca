// Implement a task directly — no planning stage — with up to three review rounds.
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.1.6"
//> using jvm 21

/** Minimal implement-and-review flow.
  *
  * No `Plan` stage: the prompt itself is the one and only task, handed straight
  * to the coder. Useful for small, already-well-scoped changes (authoring a
  * flow file, a one-line fix) where splitting into a plan first is pure
  * overhead.
  *
  * ```bash
  * scala-cli run simple.sc -- "Add a .gitignore entry for build artifacts"
  * ```
  *
  * Requires the configured role agents logged in (`claude` by default).
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  // Seeded with the prompt (rather than run with it), so the task survives a
  // resume even when a later fix-loop turn doesn't restate it.
  val session = codingAgent.session("implementer", seed = userPrompt)
  stage("Implement"):
    session.run("Implement the task from the seed prompt above.")
    reviewAndFixLoop(
      coderSession = session,
      reviewers = allReviewers(reviewAgent),
      // No planning stage, so the prompt is the whole task.
      task = Task(Title(userPrompt), ""),
      maxIterations = 3
    )
