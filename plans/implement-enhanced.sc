//> using dep "org.virtuslab::orca:0.0.8"
//> using jvm 21

/** Persistent planning + coding flow, enhanced with a plan review and a shared
  * codebase briefing.
  *
  * Same backbone as `implement.sc` (autonomous planning → persistent
  * `.orca/plan-<hash>.md` → per-task implement + review-and-fix loop), with two
  * additions that both run on the **planner's** read-only session, so they cost
  * no extra codebase exploration:
  *
  *   1. **Plan review.** After the draft plan is produced, the planner is asked
  *      to critique it and return an improved version (missing/duplicated tasks,
  *      ordering, vague descriptions, steps that don't fit the code). The
  *      improved plan is what gets persisted and implemented.
  *   1. **Shared codebase briefing.** The planner also emits a one-off briefing
  *      (modules, file paths, key APIs, conventions) and it is prepended to
  *      every task prompt — so the coding agents, which start cold, don't
  *      re-discover what the planner already learned. The briefing deliberately
  *      excludes the task descriptions: each task's own prompt is the only
  *      task-specific instruction.
  *
  * The briefing is cached next to the plan (`.orca/context-<hash>.md`) so a
  * resumed run reuses it without a live planner session; both files are removed
  * when the plan completes.
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
  // Same hash as the plan file, so the briefing tracks its plan.
  val contextFile = planFile / os.up / planFile.last.replace("plan-", "context-")

  /** Ask the planner to critique its own draft (it still holds the exploration
    * it did while planning) and return a refined plan. A self-review on the
    * planning session is cheaper and better-grounded than a cold reviewer; swap
    * in a fresh `claude.withReadOnly` session if independent review matters more.
    */
  def reviewPlan(
      plannerSession: SessionId[BackendTag.ClaudeCode.type],
      draft: Plan
  ): Plan =
    stage("Review and refine the plan"):
      val (_, improved) = claude.withReadOnly.resultAs[Plan].autonomous.run(
        s"""You produced the development plan below while exploring this
           |codebase. Review it critically and return an improved version.
           |
           |Look for: missing tasks; tasks that should be split or merged; wrong
           |ordering or unmet dependencies between tasks; vague descriptions; and
           |steps that don't fit how this codebase is actually structured. Tighten
           |each task description so an engineer could act on it without guessing.
           |
           |Keep the same epicId unless it is clearly wrong. Return the complete
           |improved plan (every task), not just the changes.
           |
           |Plan under review:
           |${Plan.render(draft)}""".stripMargin,
        session = plannerSession
      )
      improved

  /** Reuse the planner session to write a codebase briefing for the coding
    * agents. They start cold, so this captures what the planner learned —
    * without repeating the task prompts (they already have those).
    */
  def captureBriefing(
      plannerSession: SessionId[BackendTag.ClaudeCode.type]
  ): String =
    stage("Capture a codebase briefing for the coding agents"):
      val (_, briefing) = claude.withReadOnly.autonomous.run(
        s"""The plan we just refined will be implemented by separate coding
           |agents that each start with an empty context — they have NOT seen
           |your exploration. Write a single briefing about THIS CODEBASE so they
           |don't have to re-discover it.
           |
           |Include, as concise notes:
           |  - the modules/directories involved and what each is responsible for;
           |  - the specific files (with paths) they'll read or change, and why;
           |  - key types, functions, and APIs they'll build on, with signatures;
           |  - conventions to follow (error handling, naming, testing, build);
           |  - anything non-obvious you learned that would otherwise cost a re-read.
           |
           |Do NOT restate the task descriptions or the plan — they already have
           |those. Write only codebase facts, as plain markdown.""".stripMargin,
        session = plannerSession
      )
      briefing

  // Resume an existing plan (already reviewed on its first run; briefing read
  // from the sidecar), or plan fresh: draft → review → briefing, persisting
  // both. Mirrors `Plan.recoverOrCreate`'s create branch (ensureClean before the
  // read-only planner runs, checkout + write after) but threads the briefing
  // through alongside the plan.
  val (plan, briefing) = stage("Acquire plan and briefing"):
    Plan.recover(planFile) match
      case Some(resumed) =>
        val cached = if os.exists(contextFile) then os.read(contextFile) else ""
        (resumed, cached)
      case None =>
        val _ = git.ensureClean("orca: starting implementation")
        val Sessioned(plannerSession, draft) =
          Plan.autonomous.from(userPrompt, claude)
        val improved = reviewPlan(plannerSession, draft)
        val text = captureBriefing(plannerSession)
        git.checkoutOrCreate(improved.epicId)
        os.write.over(planFile, Plan.render(improved), createFolders = true)
        os.write.over(contextFile, text, createFolders = true)
        (improved, text)

  // Fresh implementer session — the planner's was read-only (plan mode).
  val session = claude.newSession

  /** Prepend the shared briefing to a task prompt. The briefing carries the
    * codebase context once; the task description is the only task-specific part.
    */
  def briefed(taskDescription: String): String =
    if briefing.isEmpty then taskDescription
    else s"$briefing\n\n---\n\n$taskDescription"

  Plan.implementTaskLoop(planFile, plan): task =>
    stage(s"Implement task: ${task.title}"):
      stage("Implementation"):
        val _ = claude.autonomous.run(briefed(task.description), session)

      // Format before review so reviewers (and the commit) don't burn turns
      // on style nits.
      stage("Format"):
        val _ = os.proc("cargo", "fmt").call(check = false)

      reviewAndFixLoop(
        coder = claude,
        sessionId = session,
        reviewers = allReviewers(claude),
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value,
        lintCommand = Some("cargo check --tests"),
        lintLlm = Some(claude.haiku)
      )

  // `implementTaskLoop` removes the plan file when the plan completes; drop the
  // briefing sidecar too so a finished prompt leaves `.orca/` clean.
  val _ = os.remove(contextFile)
