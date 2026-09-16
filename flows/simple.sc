// Implement a task directly — no planning stage — review it, then open a PR.
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.1.7"
//> using jvm 21

/** Minimal implement-and-review flow.
  *
  * No `Plan` stage: the prompt itself is the one and only task, handed straight
  * to the coder. Useful for small, already-well-scoped changes (authoring a
  * flow file, a one-line fix) where splitting into a plan first is pure
  * overhead.
  *
  * The run then opens a PR when the repository is on GitHub and hands back the
  * branch it started from — the work is on the PR, its description listing
  * whatever the review left unfixed. Otherwise it says so in one line and ends
  * on the feature branch; the work is committed either way. Under `orca
  * create` / `orca fork` that is always the skip: the authoring sandbox is a
  * local repository with no remote.
  *
  * ```bash
  * scala-cli run simple.sc -- "Add a .gitignore entry for build artifacts"
  * ```
  *
  * Requires the configured role agents logged in (`claude` by default); `gh` is
  * optional.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  // Seeded with the prompt (rather than run with it), so the task survives a
  // resume even when a later fix-loop turn doesn't restate it.
  val session = codingAgent.session("implementer", seed = userPrompt)
  // This flow's review runs inside the implement stage rather than after it.
  val openFindings = stage("Implement"):
    session.run("Implement the task from the seed prompt above.")
    reviewAndFixLoop(
      coderSession = session,
      reviewers = allReviewers(reviewAgent),
      // No planning stage, so the prompt is the whole task.
      task = Task(Title(userPrompt), ""),
      // The library default, deliberately: this loop is stage-scoped over a
      // small, already-scoped change, not a whole-run final review.
      maxIterations = 3
    )

  openPrIfGitHub(
    summarisingAgent = codingAgent.cheap,
    openFindings = openFindings
  )
