//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.0.15"
//> using jvm 21

/** Autonomous planning + coding flow.
  *
  * Mirrors the README example. The agent breaks the prompt into tasks; the task
  * list and implementation progress are tracked in the stage log
  * (`.orca/progress-<hash>.json`), committed on the branch after every stage. A
  * re-run with the same prompt resumes from the first incomplete stage — no
  * plan file, no checkbox state to keep in sync.
  *
  * Each task is implemented on a single feature branch (auto-created from the
  * prompt) with a review-and-fix loop; code + the updated progress entry are
  * committed together. The branch is auto-deleted if no code landed.
  *
  * `examples/runnable/01-simple/create-test-project.sh` seeds the calculator
  * crate into a temp dir and copies this script alongside it; from there:
  *
  * ```bash
  * scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
  * ```
  *
  * The review loop's format and lint commands come from
  * `.orca/settings.properties`, auto-discovered on first run — the script
  * itself stays stack-agnostic.
  *
  * Requires `claude` logged in and `cargo` on PATH.
  *
  * For the variant where the planner can ask clarifying questions, see
  * `implement-interactive.sc`.
  */

import orca.{*, given}

// `_.claude` selects the leading agent (the coding harness — claude, codex, pi,
// …). Inside the body, reference it as `agent`, not `claude`, so the flow is
// backend-agnostic: switch the selector to `_.codex` / `_.opencode` / … and the
// whole body follows. `agent.cheap` is the lead's own cheap tier.
flow(OrcaArgs(args), _.claude):
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, agent).value

  // Get-or-create the implementer session, seeded with the plan brief (primed
  // on first use, replayed if the backend session is lost on resume).
  val session = agent.session("implementer", seed = plan.brief)

  for task <- plan.tasks do
    stage(s"Task: ${task.title}"): // skipped on resume if already done
      session.run(task.description)
      reviewAndFixLoop( // runs under this stage
        coderSession = session,
        reviewers = allReviewers(agent),
        // reviewerSelection defaults to agentDriven(agent.cheap); pass
        // `ReviewerSelector.allEveryRound` to run every reviewer instead.
        // Format and lint default to the project's stack settings
        // (`.orca/settings.properties`); pass `Configured.Off`/`.Use(...)`
        // to opt out or override per call.
        task = task.title.value
      )
      // one commit per task: code + progress entry
