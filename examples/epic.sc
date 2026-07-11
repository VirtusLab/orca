//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.0.14"
//> using jvm 21

/** Run an epic: a multi-task workstream with cross-agent review.
  *
  * Two layers stack here:
  *
  *   - **Resumable stages.** The stage log (`.orca/progress-<hash>.json`) tracks
  *     the plan and every completed task. A re-run with the same prompt resumes
  *     from the first incomplete stage — the plan is not re-generated, already
  *     finished tasks are skipped, and the implementer session is reseeded from
  *     the recorded brief.
  *   - **Cross-agent review.** Claude (opus) plans and implements; codex reviews
  *     — the implementer is its own worst critic, so a separate model widens
  *     coverage cheaply. Fixes go back to the same Claude session. Both CLIs
  *     must be logged in.
  *
  * On success a docs step updates the README based on what changed.
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

flow(OrcaArgs(args), _.claude):
  val plan = stage("Plan"):
    // `.value` drops the planner's read-only session — the implementer
    // below mints a fresh one.
    Plan.autonomous.from(userPrompt, claude.opus).value

  // Stable coder session reused across every task (and the docs pass), seeded
  // with the plan brief; replayed on resume if the backend session is lost.
  val session = plan.implementerSession(claude)

  // Reviewers on codex; fixes go back to the Claude session that implemented.
  val reviewers: List[Agent[?]] = allReviewers(codex)

  for task <- plan.tasks do
    stage(s"Task: ${task.title}"):      // skipped on resume if already done
      session.run(task.description)
      // reviewerSelection defaults to agentDriven(claude.cheap) — the coder
      // session's cheap tier.
      reviewAndFixLoop(
        coderSession = session,
        reviewers = reviewers,
        task = task.title.value,
        // Format after every edit; Spotless is wired into the seed pom.
        formatCommand = Some("mvn -q spotless:apply")
      )
      // one commit per task: code + progress entry

  stage("Update documentation"):
    session.run(
      "All tasks done. Update project docs (README, doc-comments) based " +
        "on the changes made. Only update what's affected — no new sections."
    )
