//> using dep "org.virtuslab::orca:0.0.3"
//> using jvm 21

/** Interactive planning + coding flow (persistent).
  *
  * Same shape as `implement.sc` but the planner opens a conversation the user
  * can drive: if the prompt is underspecified, the agent calls the `ask_user`
  * tool to clarify before producing the plan. The resulting plan is persisted
  * to `.orca/plan-<hash>.md` so a re-run resumes from the first incomplete
  * task.
  *
  * Lives alongside the seeded calculator crate so a user can run it from the
  * project's root after `examples/02-interactive/create-test-project.sh`:
  *
  * ```bash
  * scala-cli run implement-interactive.sc -- "Add a new arithmetic operation to the calculator crate. Ask the user which."
  * ```
  *
  * The trailing "Ask the user which." pushes the planner to call `ask_user`
  * rather than guessing which operation to add.
  *
  * Requires `claude` logged in and `cargo` on PATH.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val planFile = Plan.defaultPath(userPrompt)

  // 1. Acquire the plan. Recover from a previous run if one exists; otherwise
  // open an interactive planning conversation so the agent can clarify the
  // prompt with `ask_user` calls before producing the plan.
  val plan = stage("Acquire plan"):
    Plan.recoverOrCreate(planFile, "orca: starting implementation"):
      Plan.interactive.from(userPrompt, claude)._2

  // 2. Iterate. A single autonomous session runs across every task — the
  // interactive ask_user surface was only needed during planning. The
  // session is started lazily by the first task and reused; the implementer
  // + the fixer in `reviewAndFixLoop` share it so review comments land
  // against the same context that produced the code. `runPersistent` commits
  // + ticks per task and removes the plan file at the end.
  var sessionId: Option[SessionId[BackendTag.ClaudeCode.type]] = None

  Plan.runPersistent(planFile, plan): task =>
    stage(s"Implement task: ${task.title}"):
      val sid = stage("Implementation"):
        sessionId match
          case Some(s) =>
            val _ = claude.autonomous.continueSession(s, task.description)
            s
          case None =>
            val (fresh, _) = claude.autonomous.startSession(task.description)
            sessionId = Some(fresh)
            fresh

      reviewAndFixLoop(
        coder = claude,
        sessionId = sid,
        reviewers = allReviewers(claude),
        // Haiku picks which reviewers run per task — sees each one's
        // description plus the changed files. Swap for
        // `ReviewerSelector.allEveryRound` to run every reviewer.
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value,
        lintCommand = Some("cargo test --quiet"),
        lintLlm = Some(claude.haiku)
      )
