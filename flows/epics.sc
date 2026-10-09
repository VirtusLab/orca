// Plan a large change as epics, then plan, build and review each epic in turn.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.2.0"
//> using jvm 21

/** For a change too large for one plan. Each epic is planned just before it
  * runs, so its plan sees the code earlier epics wrote.
  *
  * ```bash
  * scala-cli run --workspace "$(mktemp -d)" epics.sc -- "Add user accounts: storage, sign-up, login and password reset"
  * ```
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val roadmap = stage("Plan epics"):
    val planned =
      Plan.autonomous.roadmap(userPrompt, planningAgent).reviewed().value
    if planned.epics.isEmpty then
      fail("The planner produced no epics; re-run to plan again, or reword the prompt")
    planned

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

        // Review the whole epic before the next one builds on it.
        val epicFixer = codingAgent.session("epic-fixer", seed = seed)
        reviewAndFixLoop(
          coderSession = epicFixer,
          reviewers = allReviewers(reviewAgent),
          task = Task(epic.title, epic.goal),
          maxFixTurns = 3,
          priorOpenFindings = taskOpenFindings.flatMap(_.findings)
        )

  stage("Update documentation"):
    val documenter = codingAgent.session("documenter", seed = roadmap.brief)
    documenter.run(
      "All epics are done and committed. Read what this branch changed " +
        "(`git diff` against its base), then update project docs (README, " +
        "doc-comments) to match. Only update what's affected — no new sections."
    )

  // Nothing reviews after this loop, so it gets more fix turns.
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
