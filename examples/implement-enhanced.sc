//> using dep "org.virtuslab::orca:0.0.11"
//> using jvm 21

/** Persistent planning + coding flow, enhanced with a plan review and a shared
  * codebase brief.
  *
  * Same backbone as `implement.sc` (autonomous planning → persistent
  * `.orca/plan-<hash>.md` → per-task implement + review-and-fix loop), with two
  * steps chained onto planning — both on the planner's read-only session, so
  * they cost no extra exploration:
  *
  *   1. **`.reviewed(claude)`** — the planner critiques its own draft and
  *      returns an improved plan (missing/duplicated tasks, ordering, vague
  *      descriptions, steps that don't fit the code).
  *   1. **`.briefed(claude)`** — the planner writes a one-off codebase brief
  *      (modules, paths, key APIs, conventions), producing a `PlanWithBrief`.
  *      `plan.taskPrompt(task)` prepends it to every task so the cold-starting
  *      coding agents don't re-discover what the planner already learned.
  *
  * The brief rides in the plan file (a trailing `## Brief` section), so
  * `recoverOrCreate` / `implementTaskLoop` persist, reuse, and remove it with
  * the file — no sidecar.
  *
  * Swap to `.briefed(claude).reviewed(claude)` to also review the brief; both
  * are well-typed.
  *
  * ```bash
  * scala-cli run implement-enhanced.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * Requires `claude` logged in and `cargo` on PATH.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val planFile = Plan.defaultPath(userPrompt)

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
