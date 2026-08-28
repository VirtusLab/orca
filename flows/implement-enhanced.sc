// Plan (self-reviewed), implement per task, update docs, then open a PR.
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.1.6"
//> using jvm 21

/** Autonomous planning + coding flow that lands the work on its own branch and
  * opens a pull request.
  *
  * Same backbone as `implement.sc` (autonomous planning → per-task implement +
  * single review pass → whole-run review loop), with a self-review pass on the
  * plan: the planner
  * critiques its own draft and returns an improved one (missing or duplicated
  * tasks, ordering, vague descriptions, steps that don't fit the code).
  *
  * On success the flow:
  *
  *   1. Updates the project's docs (README, doc-comments) from what the tasks
  *      changed, as its own stage and commit — so the docs land in the PR.
  *   1. Reviews everything the run changed, docs included.
  *   1. Pushes the feature branch.
  *   1. Opens a PR with a cheap-model-generated title + description from the
  *      full branch diff. A human picks the PR up from there.
  *
  * ```bash
  * scala-cli run implement-enhanced.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * Requires the configured role agents logged in (`claude` by default) and
  * `gh` authenticated.
  */

import orca.{*, given}

// Opens a PR at the end, so return to the starting branch afterward (the
// default is to stay on the feature branch, for no-PR flows).
flow(OrcaArgs(args), returnToStartBranch = true):
  val plan = stage("Plan"):
    Plan.autonomous
      .from(userPrompt, planningAgent)
      .reviewed(planningAgent)
      .value

  val session = codingAgent.session("implementer", seed = plan.brief)

  val taskDeclines =
    for task <- plan.tasks yield
      stage(s"Task: ${task.title}"):
        session.run(task.description)
        reviewThenFix(
          coderSession = session,
          reviewers = allReviewers(reviewAgent),
          task = task
        )

  // Docs pass on the implementer session — it already carries the brief and
  // every task's context. Its own stage, so the docs commit exists before the
  // push below.
  stage("Update documentation"):
    session.run(
      "All tasks done. Update project docs (README, doc-comments) based " +
        "on the changes made. Only update what's affected — no new sections."
    )

  // Everything the run changed, reviewed in one loop: each task's single pass
  // took the fixer's word for its own fixes, and this is what checks them. The
  // per-task declines seed the loop, so its reviewers don't re-report findings
  // the fixer already answered.
  stage("Final review"):
    reviewAndFixLoop(
      coderSession = session,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title("The whole planned change"), plan.brief),
      diff = ReviewDiff.WholeRun,
      maxIterations = 3,
      priorDeclines = IgnoredIssues(taskDeclines.flatMap(_.issues))
    )

  openPrFromBranch(summarisingAgent = codingAgent.cheap)
