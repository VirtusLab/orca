package orca.shell.menu

import orca.shell.resume.InterruptedRun
import orca.shell.ui.Choice
import orca.util.TextUtil

/** Main menu selection (ADR 0021 §3). */
private[menu] enum MenuItem:
  case RunFlow, ResumeRun, ViewFlow, EditFlow, CreateFlow, ForkFlow,
    ContinueSession, Reconfigure, EditSettings, RediscoverStack, Exit

private[menu] object MainMenu:

  /** Fixed ADR §3 order. Conditional items are ABSENT when inapplicable, never
    * shown disabled: `resumeOffer` non-None inserts `ResumeRun` right after
    * `RunFlow`, and `continueSessionCount` non-None (the newest attempt's
    * session count; the picker still lists every attempt's sessions) inserts
    * `ContinueSession` (ADR 0021 §3/§8 amendments 2026-07-27).
    */
  def choices(
      continueSessionCount: Option[Int],
      resumeOffer: Option[InterruptedRun],
      // The shell's own directory, to tell a resume that happens here from one
      // that happens in a worktree.
      workDir: os.Path
  ): List[Choice[MenuItem]] =
    val continueChoice = continueSessionCount.map(count =>
      Choice(
        MenuItem.ContinueSession,
        s"Continue a session from the last attempt with sessions ($count session(s))"
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

  /** `"Resume interrupted run — <flow>: <first ~40 chars of task> on
    * <branch>"`, plus ` (in <dir>)` when the log is in another directory. The
    * task and branch come from a committed header, and the task is often
    * multi-line ([[RunMenu.runFlow]] reads multi-line), so both reach the menu
    * row through [[TextUtil.onelinePreview]].
    */
  private def resumeLabel(run: InterruptedRun, workDir: os.Path): String =
    val task = TextUtil.onelinePreview(run.userPrompt, 40)
    val branch = TextUtil.onelinePreview(run.branch.value, 60)
    // The log can be in one of orca's worktrees, and the run resumes THERE —
    // an offer that read like any other would send the user's work to a
    // directory they were never shown.
    val where = if run.dir == workDir then "" else s" (in ${run.dir.last})"
    // Unclipped: a recorded path is run as is, so the user must see all of it.
    s"Resume interrupted run — ${run.flow.display}: $task on $branch$where"
