// Plan (self-reviewed), implement per task, update docs, then open a PR.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.7"
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
  *   1. When the repository is on GitHub: pushes the feature branch, opens a PR
  *      with a cheap-model-generated title + description from the full branch
  *      diff plus a section listing whatever the final review left unfixed, and
  *      hands back the branch the run started from — a human picks the PR up
  *      from there. Otherwise nothing is pushed: the run says so in one line
  *      and ends on the feature branch, work committed either way.
  *
  * ```bash
  * scala-cli run implement-enhanced.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * Requires the configured role agents logged in (`claude` by default); `gh` is
  * optional.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val plan = stage("Plan"):
    Plan.autonomous
      .from(userPrompt, planningAgent)
      .reviewed(planningAgent)
      .value

  // One coder session per task, one for the docs pass and one for the final
  // review: a session spanning the run re-sends every earlier task's transcript
  // on every later API call.
  val taskSessions =
    List.fill(plan.tasks.size)(
      codingAgent.session("implementer", seed = plan.brief)
    )
  val documenter = codingAgent.session("documenter", seed = plan.brief)
  val finalFixer = codingAgent.session("final-fixer", seed = plan.brief)

  val taskDeclines =
    for (task, session) <- plan.tasks.zip(taskSessions) yield
      stage(s"Task: ${task.title}"):
        session.run(task.description)
        reviewThenFix(
          coderSession = session,
          reviewers = allReviewers(reviewAgent),
          task = task
        )

  // Its own stage, so the docs commit exists before the push below. The session
  // carries the brief but implemented none of the tasks, so the prompt points
  // it at the branch diff for what actually changed.
  stage("Update documentation"):
    documenter.run(
      "All tasks are done and committed. Read what this branch changed " +
        "(`git diff` against its base), then update project docs (README, " +
        "doc-comments) to match. Only update what's affected — no new sections."
    )

  // Everything the run changed, reviewed in one loop: each task's single pass
  // took the fixer's word for its own fixes, and this is what checks them. The
  // per-task declines seed the loop, so its reviewers don't re-report findings
  // the fixer already answered. A higher cap than the library default: nothing
  // reviews again after this loop.
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
