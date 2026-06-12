//> using dep "org.virtuslab::orca:0.0.12"
//> using jvm 21

/** Run an epic: a multi-task workstream with cross-agent review.
  *
  * Two layers stack here:
  *
  *   - **On-disk epic.** `.orca/plan-<hash>.md` holds the task list — generated
  *     on a fresh run, recovered on a resume (pending edits stashed, branch
  *     re-attached) to restart from the first incomplete task. Each task's
  *     `Status: [x]` is committed as the task lands, so a crash loses no
  *     progress.
  *   - **Cross-agent review.** Claude implements; codex reviews — the
  *     implementer is its own worst critic, so a separate model widens coverage
  *     cheaply. Fixes go back to the same Claude session. Both CLIs must be
  *     logged in.
  *
  * On success the plan file is removed, then a docs step updates the README
  * based on what changed.
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

  // Stable coder session reused across every task (and the docs pass) so the
  // agent retains context. Fresh — not the planner's (read-only). The runtime
  // owns git: the default system prompt tells the agent not to commit, so a
  // stray `git commit` can't empty the tree before `implementTaskLoop` does.
  val session = claude.newSession

  // Reviewers on codex; fixes go back to the Claude session that implemented.
  val reviewers: List[LlmTool[?]] = allReviewers(codex)

  Plan.implementTaskLoop(planFile, plan): task =>
    stage(s"Implement task: ${task.title}"):
      stage("Implementation"):
        val _ = claude.autonomous.run(task.description, session)

      reviewAndFixLoop(
        coder = claude,
        sessionId = session,
        reviewers = reviewers,
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value,
        // Format after every edit; Spotless is wired into the seed pom.
        formatCommand = Some("mvn -q spotless:apply")
      )

  stage("Update documentation"):
    val _ = claude.autonomous.run(
      "All tasks done. Update project docs (README, doc-comments) based " +
        "on the changes made. Only update what's affected — no new sections.",
      session
    )
    git.commit("docs: update for completed work").orThrow
