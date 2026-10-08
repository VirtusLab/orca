// Plan a large change as epics, then plan, build and review each epic in turn.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.10"
//> using jvm 21

/** Epic-sized planning + coding flow, for a change too large for one plan.
  *
  * The planner splits the prompt into epics and critiques that outline. Each
  * epic is planned into tasks just before it runs, so its planner reads the
  * code earlier epics produced. Every task gets one review pass, every epic a
  * review loop over everything it changed, and the whole run a final review
  * loop. A documentation stage updates the project's docs before the final
  * review.
  *
  * A PR follows when the repository is on GitHub; otherwise the run says so and
  * ends on the feature branch, work committed either way.
  *
  * ```bash
  * scala-cli run --workspace "$(mktemp -d)" epic.sc -- "Add user accounts: storage, sign-up, login and password reset"
  * ```
  *
  * Requires the configured role agents logged in (`claude` by default); `gh` is
  * optional.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val roadmap = stage("Plan epics"):
    Plan.autonomous.roadmap(userPrompt, planningAgent).reviewed().value
  if roadmap.epics.isEmpty then fail("The planner produced no epics")

  val epicOpenFindings =
    for epic <- roadmap.epics yield
      stage(s"Epic: ${epic.title}"):
        val plan = stage("Plan"):
          Plan.autonomous
            .from(roadmap.epicPrompt(epic), planningAgent)
            .reviewed()
            .value
        val seed = s"${roadmap.brief}\n\n---\n\n${plan.brief}"

        val taskOpenFindings =
          for task <- plan.tasks yield
            stage(s"Task: ${task.title}"):
              val session = codingAgent.session("implementer", seed = seed)
              session.run(task.description)
              reviewThenFix(
                coderSession = session,
                reviewers = allReviewers(reviewAgent),
                task = task
              )

        // Before the next epic builds on this one: everything it changed.
        val epicFixer = codingAgent.session("epic-fixer", seed = seed)
        reviewAndFixLoop(
          coderSession = epicFixer,
          reviewers = allReviewers(reviewAgent),
          task = Task(epic.title, epic.goal),
          maxFixTurns = 3,
          priorOpenFindings = taskOpenFindings.flatMap(_.findings)
        )

  // Its own stage, so the docs commit exists before the push below. The
  // documenter implemented none of the epics, so the prompt points it at the
  // branch diff for what actually changed.
  stage("Update documentation"):
    val documenter = codingAgent.session("documenter", seed = roadmap.brief)
    documenter.run(
      "All epics are done and committed. Read what this branch changed " +
        "(`git diff` against its base), then update project docs (README, " +
        "doc-comments) to match. Only update what's affected — no new sections."
    )

  // Nothing reviews again after this loop, hence the raised fix-turn cap.
  val openFindings = stage("Final review"):
    val finalFixer = codingAgent.session("final-fixer", seed = roadmap.brief)
    reviewAndFixLoop(
      coderSession = finalFixer,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title("The whole planned change"), roadmap.description),
      diff = ReviewDiff.WholeRun,
      maxFixTurns = 5,
      priorOpenFindings = epicOpenFindings.flatMap(_.findings)
    )

  openPrIfGitHub(
    summarisingAgent = codingAgent,
    openFindings = openFindings
  )
