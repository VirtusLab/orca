//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.0.14"
//> using jvm 21

/** Autonomous planning + coding flow that lands the work on its own branch and
  * opens a pull request.
  *
  * Same backbone as `implement.sc` (autonomous planning → per-task implement
  * + review-and-fix loop), enhanced with a self-review pass on the plan:
  *
  *   1. **`.reviewed(agent)`** — the planner critiques its own draft and
  *      returns an improved plan (missing/duplicated tasks, ordering, vague
  *      descriptions, steps that don't fit the code). Runs read-only on the
  *      planning session; no extra exploration cost.
  *
  * In addition, the plan always carries a `brief` — a codebase summary the
  * planner writes as part of its structured output. It seeds the implementer
  * session so the cold-starting coding agents don't re-discover what the
  * planner already learned.
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

// Opens a PR at the end, so return to the starting branch afterward (the
// default is to stay on the feature branch, for no-PR flows like implement.sc).
// `_.claude` selects the leading agent; the body references it as `agent` (not
// `claude`) so the flow is backend-agnostic — switch the selector to `_.codex` /
// `_.opencode` / … and the whole flow follows.
flow(OrcaArgs(args), _.claude, returnToStartBranch = true):
  // Plan → review, all on one read-only planner session. The Plan structured
  // output always includes a brief, which seeds the implementer session below.
  val plan = stage("Plan"):
    Plan.autonomous
      .from(userPrompt, agent)
      .reviewed(agent)
      .value

  // Get-or-create the implementer session. Seeded with the brief so the agent
  // has codebase context from the start; replayed on resume if the backend
  // session is lost.
  val session = agent.session("implementer", seed = plan.brief)

  for task <- plan.tasks do
    stage(s"task: ${task.title}"):      // skipped on resume if already done
      // The session seed already carries the brief, so send only the task
      // description here — session.run re-prepends the seed on first use / resume.
      session.run(task.description)
      reviewAndFixLoop(
        coderSession = session,
        reviewers = allReviewers(agent),
        reviewerSelection = ReviewerSelector.agentDriven(agent.cheap),
        task = task.title.value,
        // Format after every edit (the implementation and each review fix).
        formatCommand = Some("cargo fmt"),
        lintCommand = Some("cargo check --tests"),
        lintAgent = Some(agent.cheap)
      )
      // one commit per task: code + progress entry

  // Push the branch and open the PR from the full branch diff.
  stage("Push branch"):
    git.push().orThrow

  val prSum = stage("Generate PR title and description"):
    summarisePr(agent = agent.cheap, diff = git.diffVsBase(git.defaultBase()))

  stage("Open PR"):
    // gh.createPr is idempotent by head branch (R24): if the branch already has
    // an open PR, the existing handle is returned rather than failing.
    gh.createPr(title = prSum.title, body = prSum.body).orThrow
