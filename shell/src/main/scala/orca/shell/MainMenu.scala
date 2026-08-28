package orca.shell

import orca.shell.resume.InterruptedRun
import orca.shell.ui.Choice
import orca.util.TextUtil

/** Main menu selection (ADR 0021 §3, `ForkFlow` added §6/§9, `RediscoverStack`
  * added §8, `EditSettings` added §4, `ResumeRun` added §3 amendment
  * 2026-07-27).
  */
private[shell] enum MenuItem:
  case RunFlow, ResumeRun, ViewFlow, EditFlow, CreateFlow, ForkFlow,
    ContinueSession, Reconfigure, EditSettings, RediscoverStack, Exit

/** How Edit/Create/Fork make their changes (ADR 0021 §6/§9 amendment): asked
  * via [[MainMenu.modeChoices]] after the action's WHAT is established (which
  * flow to edit; source+tier to fork; nothing yet for create, where the mode
  * decides whether a goal or a filename comes next).
  */
private[shell] enum ChangeMode:
  case Hand, Agent

/** Where a run's work goes, asked via [[MainMenu.runTargetChoices]] once the
  * flow and the task text are settled.
  *
  * One choice on one axis, rather than a branch confirm followed by a worktree
  * confirm: the two answers are not independent — orca refuses `--worktree`
  * together with `--skip-branch` — and asking them separately makes the illegal
  * pair expressible, leaving prompt order to prevent it. A worktree run creates
  * a branch of its own, so all three cases here are branch-creating or not in
  * exactly one way.
  */
private[shell] enum RunTarget:
  /** A branch orca creates in this checkout — the default. */
  case NewBranch

  /** The branch checked out now; the flow commits onto it (`--skip-branch`). */
  case CurrentBranch

  /** A separate checkout under `.orca/worktrees/` (`--worktree`). */
  case Worktree

  /** Runs on the branch already checked out, instead of a new one. */
  def skipBranch: Boolean = this == RunTarget.CurrentBranch

  /** Runs in a worktree rather than the invoking checkout. */
  def worktree: Boolean = this == RunTarget.Worktree

private[shell] object MainMenu:

  /** Fixed ADR §3 order. Conditional items are ABSENT when inapplicable, never
    * shown disabled: `resumeOffer` non-None inserts `ResumeRun` right after
    * `RunFlow`, and `continueSessionCount` non-None (the newest run's session
    * count; the picker below still lists every run's sessions) inserts
    * `ContinueSession` (ADR 0021 §3/§8 amendments 2026-07-27).
    */
  def choices(
      continueSessionCount: Option[Int],
      resumeOffer: Option[InterruptedRun] = None,
      // The shell's own directory, to tell a resume that happens here from one
      // that happens in a worktree. Defaulted for the tests that don't care.
      workDir: os.Path = os.pwd
  ): List[Choice[MenuItem]] =
    val continueChoice = continueSessionCount.map(count =>
      Choice(
        MenuItem.ContinueSession,
        s"Continue a session from the last flow run ($count session(s))"
      )
    )
    val resumeChoice =
      resumeOffer.map(run =>
        Choice(MenuItem.ResumeRun, resumeLabel(run, workDir))
      )
    List(
      Choice(MenuItem.RunFlow, "Run a flow")
    ) ++ resumeChoice.toList ++ List(
      Choice(MenuItem.ViewFlow, "View a flow"),
      Choice(
        MenuItem.EditFlow,
        "Edit a flow — by hand, or an agent makes the changes"
      ),
      Choice(
        MenuItem.CreateFlow,
        "Create a new flow — by hand, or an agent writes it"
      ),
      Choice(
        MenuItem.ForkFlow,
        "Fork a flow — by hand, or an agent adapts the copy"
      )
    ) ++ continueChoice.toList ++ List(
      Choice(
        MenuItem.Reconfigure,
        "Re-configure — pick the agents & models for planning/coding/review"
      ),
      Choice(
        MenuItem.EditSettings,
        "Edit settings — open the project or global settings file in your editor"
      ),
      Choice(
        MenuItem.RediscoverStack,
        "Clear stack settings (format/lint/test) — re-detected on the next flow run"
      ),
      Choice(MenuItem.Exit, "Exit")
    )

  /** "How should the changes be made?" — the two-row hand-vs-agent prompt
    * shared by Edit/Create/Fork (ADR 0021 §6/§9 amendment). Agent first: it is
    * the default choice (the interactive select has no cursor preselection, so
    * first position IS the default).
    */
  val modeChoices: List[Choice[ChangeMode]] = List(
    Choice(
      ChangeMode.Agent,
      "With an agent — describe the changes and let it work"
    ),
    Choice(ChangeMode.Hand, "By hand — open in your editor")
  )

  /** [[RunTarget]]'s rows, in the order they are offered. `NewBranch` leads
    * because it is the default, and the position is load-bearing rather than
    * cosmetic: `ConsoleUiShell` cannot honor `preselect`, so the first row is
    * what the cursor starts on and what Enter picks.
    */
  val runTargetChoices: List[Choice[RunTarget]] = List(
    Choice(RunTarget.NewBranch, "A new branch in this checkout"),
    Choice(
      RunTarget.CurrentBranch,
      "The branch checked out now — the flow commits onto it"
    ),
    Choice(
      RunTarget.Worktree,
      "A new worktree — a separate checkout under .orca/worktrees/, " +
        "leaving this one untouched"
    )
  )

  /** `"Resume interrupted run — <flow>: <first ~40 chars of task>"`. The task
    * comes from a committed header and is often multi-line (`Main.promptTask`
    * reads multi-line), so it reaches the menu row through
    * [[TextUtil.onelinePreview]].
    */
  private def resumeLabel(run: InterruptedRun, workDir: os.Path): String =
    val task = TextUtil.onelinePreview(run.userPrompt, 40)
    // The log can be in one of orca's worktrees, and the run resumes THERE —
    // an offer that read like any other would send the user's work to a
    // directory they were never shown.
    val where = if run.dir == workDir then "" else s" (in ${run.dir.last})"
    s"Resume interrupted run — ${run.flowName}: $task$where"
