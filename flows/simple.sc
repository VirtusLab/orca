// Implement a task directly — no planning stage — review it, then open a PR.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.8"
//> using jvm 21

/** Minimal implement-and-review flow.
  *
  * No `Plan` stage: the prompt itself is the one and only task, handed straight
  * to the coder. Useful for small, already-well-scoped changes (authoring a
  * flow file, a one-line fix) where splitting into a plan first is pure
  * overhead.
  *
  * A PR follows when the repository is on GitHub; otherwise the run says so and
  * ends on the feature branch, work committed either way. Under `orca create` /
  * `orca fork` it is always the latter: the authoring sandbox has no remote.
  *
  * ```bash
  * scala-cli run --workspace "$(mktemp -d)" simple.sc -- "Add a .gitignore entry for build artifacts"
  * ```
  *
  * Requires the configured role agents logged in (`claude` by default); `gh` is
  * optional.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val openFindings = stage("Implement"):
    // Seeded with the prompt rather than run with it, so the task survives a
    // resume even when a later fix-loop turn doesn't restate it.
    val session = codingAgent.session("implementer", seed = userPrompt)
    session.run("Implement the task from the seed prompt above.")
    reviewAndFixLoop(
      coderSession = session,
      reviewers = allReviewers(reviewAgent),
      // No planning stage, so the prompt is the whole task.
      task = Task(Title(userPrompt), ""),
      // Spelled out rather than inherited, like every cap in flows/.
      maxIterations = 3
    )

  openPrIfGitHub(
    summarisingAgent = codingAgent.cheap,
    openFindings = openFindings
  )
