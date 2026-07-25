// Implement a task directly — no planning stage — with a single review round.
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.0.17"
//> using jvm 21

/** Minimal implement-and-review flow.
  *
  * No `Plan` stage: the prompt itself is the one and only task, handed straight
  * to the coder. Useful for small, already-well-scoped changes (authoring a
  * flow file, a one-line fix) where splitting into a plan first is pure
  * overhead.
  *
  * The session is seeded with `userPrompt` rather than run with it, so the task
  * survives a lost/resumed backend conversation even if a later prompt (e.g. a
  * fix-loop turn) doesn't restate it — see `FlowSession`'s replay semantics.
  *
  * ```bash
  * scala-cli run simple.sc -- "Add a .gitignore entry for build artifacts"
  * ```
  *
  * The review loop's format and lint commands come from
  * `.orca/settings.properties`, auto-discovered on first run.
  *
  * Requires `claude` logged in.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val session = codingAgent.session("implementer", seed = userPrompt)
  stage("Implement"):
    session.run("Implement the task from the seed prompt above.")
    reviewAndFixLoop(
      coderSession = session,
      reviewers = allReviewers(reviewAgent),
      task = userPrompt,
      maxIterations = 1
    )
