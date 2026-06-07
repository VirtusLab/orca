//> using dep "org.virtuslab::orca:0.0.9"
//> using jvm 21

/** Run an epic: a multi-task workstream with cross-agent review.
  *
  * Two layers stack here:
  *
  *   - **On-disk epic.** `.orca/plan-<hash>.md` holds the task list; on a
  *     fresh run the agent generates it, on a resume the existing file is
  *     recovered (pending edits stashed, branch re-attached) and execution
  *     restarts from the first incomplete task. Each task's `Status: [x]`
  *     checkbox is committed back to the plan file as the task lands, so a
  *     crash mid-flow loses no progress.
  *   - **Cross-agent review.** Claude implements; codex reviews. The
  *     implementing agent is its own worst critic — running reviewers on a
  *     separate model widens coverage without much extra cost. Fixes go back
  *     to the same Claude session. Both CLIs need to be logged in.
  *
  * At the end of a successful run the plan file is removed, then the
  * documentation step updates the project README based on what changed.
  *
  * Run it from a git repository, with `claude` and `codex` logged in:
  *
  * ```bash
  * scala-cli run epic.sc -- \
  *   "Persist tasks to a JSON file at ~/.todo/tasks.json (load on startup, save on every change), \
  *    add 'done <id>' and 'delete <id>' commands, and support priority levels (low/medium/high) \
  *    with a 'list --priority' filter"
  * ```
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val planFile = Plan.defaultPath(userPrompt)

  // Resume `.orca/plan-<hash>.md` if it exists; otherwise plan + branch.
  val plan = stage("Acquire epic"):
    Plan.recoverOrCreate(planFile):
      // `.value` drops the planner's read-only session — the implementer
      // below mints a fresh one.
      Plan.autonomous.from(userPrompt, claude.opus).value

  // Stable coder session reused across every task (and the docs pass at the
  // end) so the agent retains cross-task context. Fresh session (not the
  // planner's, which ran read-only). The runtime owns git commits — the agent
  // is told not to commit by the default system prompt, so a stray `git
  // commit` can't empty the working tree before `implementTaskLoop` commits.
  val session = claude.newSession

  // Reviewers on codex (not claude — the implementer is its own worst critic);
  // fixes go back to the same Claude session that implemented the task.
  val reviewers: List[LlmTool[?]] = allReviewers(codex)

  Plan.implementTaskLoop(planFile, plan): task =>
    stage(s"Implement task: ${task.title}"):
      stage("Implementation"):
        val _ = claude.autonomous.run(task.description, session)

      // Format before review so reviewers don't burn turns on style nits the
      // toolchain would fix automatically. Spotless is wired into the seed pom.
      stage("Format"):
        val _ = os
          .proc("mvn", "-q", "spotless:apply")
          .call(cwd = os.pwd, check = false)

      reviewAndFixLoop(
        coder = claude,
        sessionId = session,
        reviewers = reviewers,
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value
      )

  stage("Update documentation"):
    val _ = claude.autonomous.run(
      "All tasks done. Update project docs (README, doc-comments) based " +
        "on the changes made. Only update what's affected — no new sections.",
      session
    )
    git.commit("docs: update for completed work").orThrow
