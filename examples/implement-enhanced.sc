//> using dep "org.virtuslab::orca:0.0.14+1-2e21cd3e+20260623-1601-SNAPSHOT"
//> using jvm 21

/** Autonomous planning + coding flow that lands the work on its own branch and
  * opens a pull request.
  *
  * Same backbone as `implement.sc` (autonomous planning → per-task implement
  * + review-and-fix loop), enhanced with a self-review pass on the plan:
  *
  *   1. **`.reviewed(claude)`** — the planner critiques its own draft and
  *      returns an improved plan (missing/duplicated tasks, ordering, vague
  *      descriptions, steps that don't fit the code). Runs read-only on the
  *      planning session; no extra exploration cost.
  *
  * In addition, the plan always carries a `brief` — a codebase summary the
  * planner writes as part of its structured output. `plan.taskPrompt(task)`
  * prepends it to every task so the cold-starting coding agents don't
  * re-discover what the planner already learned.
  *
  * The flow runtime handles the feature branch automatically: it creates a
  * branch from the prompt, commits progress to the stage log, and returns to
  * the starting branch on success. Resume is stage-log based — a re-run with
  * the same prompt continues from the first incomplete stage.
  *
  * On success the flow:
  *
  *   1. Pushes the feature branch.
  *   1. Opens a PR with a haiku-generated title + description from the full
  *      branch diff. A human picks the PR up from there.
  *
  * ```bash
  * scala-cli run implement-enhanced.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * Requires `claude` logged in, `cargo` on PATH, and `gh` authenticated.
  */

import orca.{*, given}

flow(OrcaArgs(args), claude):
  // Plan → review, all on one read-only planner session. Brief is always
  // included in the Plan structured output; plan.taskPrompt(task) prepends it.
  val plan = stage("Plan"):
    Plan.autonomous
      .from(userPrompt, claude)
      .reviewed(claude)
      .value

  // Get-or-create the implementer session. Seeded with the brief so the agent
  // has codebase context from the start; replayed on resume if the backend
  // session is lost.
  val session = claude.session(seed = plan.brief)

  for task <- plan.tasks do
    stage(s"task: ${task.title}"):      // skipped on resume if already done
      // taskPrompt prepends the shared brief.
      claude.runSeeded(plan.taskPrompt(task), session)
      reviewAndFixLoop(
        coder = claude, sessionId = session,
        reviewers = allReviewers(claude),
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value,
        // Format after every edit (the implementation and each review fix).
        formatCommand = Some("cargo fmt"),
        lintCommand = Some("cargo check --tests"),
        lintLlm = Some(claude.haiku)
      )
      // one commit per task: code + progress entry

  // Push the branch and open the PR from the full branch diff.
  stage("Push branch"):
    git.push().orThrow

  val prSum = stage("Generate PR title and description"):
    summarisePr(llm = claude.haiku, diff = git.diffVsBase(git.defaultBase()))

  stage("Open PR"):
    gh.createPr(title = prSum.title, body = prSum.body).orThrow
