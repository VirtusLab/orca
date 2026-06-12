//> using dep "org.virtuslab::orca:0.0.11"
//> using jvm 21

/** Persistent planning + coding flow that lands the work on its own branch and
  * opens a pull request.
  *
  * Same backbone as `implement.sc` (autonomous planning → persistent
  * `.orca/plan-<hash>.md` → per-task implement + review-and-fix loop), enhanced
  * the same way as before — both on the planner's read-only session, so they
  * cost no extra exploration:
  *
  *   1. **`.reviewed(claude)`** — the planner critiques its own draft and
  *      returns an improved plan (missing/duplicated tasks, ordering, vague
  *      descriptions, steps that don't fit the code).
  *   1. **`.briefed(claude)`** — the planner writes a one-off codebase brief
  *      (modules, paths, key APIs, conventions), producing a `PlanWithBrief`.
  *      `plan.taskPrompt(task)` prepends it to every task so the cold-starting
  *      coding agents don't re-discover what the planner already learned.
  *
  * On top of that, like `issue-pr-bugfix.sc` but prompt-driven and with no
  * failing-test/CI stages, the flow runs on a fresh branch and finishes with a
  * PR:
  *
  *   1. On a fresh run it stashes any WIP, has haiku name a branch from the
  *      prompt, and switches to it.
  *   1. Plans + implements all tasks (each committed on the branch).
  *   1. Pushes and opens a PR with a haiku-generated title + description from
  *      the full branch diff. A human picks the PR up from there.
  *
  * Resume: an interrupted run leaves you on the orca branch with the committed
  * plan file, so re-running continues the loop in place (the `recover` guard
  * skips the stash/name/checkout). A resumed run ends on the orca branch — it
  * can't recover the original starting branch; a fresh run returns to where it
  * started. Re-running a finished flow is a no-op: `createPr` reports
  * `PrAlreadyExists`, which the flow tolerates.
  *
  * ```bash
  * scala-cli run implement-enhanced.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * Requires `claude` logged in, `cargo` on PATH, and `gh` authenticated.
  */

import orca.{*, given}
// Not in the `orca.*` export wildcard; imported by name to tolerate a re-run
// against a branch whose PR a prior run already opened.
import orca.tools.PrAlreadyExists

/** Structured branch-name suggestion — a single kebab-case slug from haiku. */
case class BranchName(name: String) derives JsonData

flow(OrcaArgs(args)):
  val planFile = Plan.defaultPath(userPrompt)
  // Captured before any branch switch so the flow can return here at the end.
  val startBranch = git.currentBranch()

  // Fresh run only: stash WIP, name a branch, switch to it. On resume the plan
  // file is already committed on the orca branch we're sitting on, so `recover`
  // short-circuits this block and the loop below continues in place — naming a
  // branch here would otherwise create a second one (haiku isn't deterministic).
  if Plan.recover(planFile).isEmpty then
    // Stash pre-existing edits before switching branches, or they'd ride onto
    // the orca branch. `ensureClean` emits a Step the user can act on
    // (`git stash pop`) once the flow finishes.
    val _ = git.ensureClean("orca: pre-implement stash")
    val branchName = stage("Name a branch"):
      claude.haiku
        .resultAs[BranchName]
        .autonomous
        .run(
          s"""Suggest a git branch name for the task below. Use a short,
             |kebab-case slug prefixed with `orca/` (e.g.
             |`orca/add-multiply-fn`). Output the name only.
             |
             |Task: $userPrompt""".stripMargin
        )
        ._2
        .name
    stage(s"Create branch $branchName"):
      git.checkoutOrCreate(branchName)

  // Plan → review → brief, all on one read-only planner session. On resume the
  // persisted plan (with its brief) is reused without re-planning.
  val plan = Plan.recoverOrCreate(planFile):
    Plan.autonomous
      .from(userPrompt, claude)
      .reviewed(claude)
      .briefed(claude)
      .value

  // Fresh implementer session — the planner's was read-only (plan mode).
  val session = claude.newSession

  Plan.implementTaskLoop(planFile, plan): task =>
    stage(s"Implement task: ${task.title}"):
      stage("Implementation"):
        // taskPrompt prepends the shared brief.
        val _ = claude.autonomous.run(plan.taskPrompt(task), session)

      reviewAndFixLoop(
        coder = claude,
        sessionId = session,
        reviewers = allReviewers(claude),
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value,
        // Format after every edit (the implementation and each review fix).
        formatCommand = Some("cargo fmt"),
        lintCommand = Some("cargo check --tests"),
        lintLlm = Some(claude.haiku)
      )

  // The task loop has removed the plan file and committed that removal, so the
  // branch now holds only the implementation. Push it and open the PR from the
  // full branch diff.
  stage("Push branch"):
    git.push().orThrow

  val prSum = stage("Generate PR title and description"):
    summarisePr(llm = claude.haiku, diff = git.diffVsBase(git.defaultBase()))

  stage("Open PR"):
    gh.createPr(title = prSum.title, body = prSum.body) match
      case Left(_: PrAlreadyExists) => () // a prior run already opened it
      case Left(e)                  => throw e
      case Right(_)                 => () // the tool logs the URL

  // Return to the start branch so any stashed WIP pops back onto it. On a
  // resumed run this is the orca branch itself, so it's a no-op.
  stage(s"Return to $startBranch"):
    git.checkout(startBranch).orThrow
