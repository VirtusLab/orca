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

private[shell] object MainMenu:

  /** Fixed ADR §3 order. Conditional items are ABSENT when inapplicable, never
    * shown disabled: `resumeOffer` non-None inserts `ResumeRun` right after
    * `RunFlow`, and `continueSessionCount` non-None (the newest run's session
    * count; the picker below still lists every run's sessions) inserts
    * `ContinueSession` (ADR 0021 §3/§8 amendments 2026-07-27).
    */
  def choices(
      continueSessionCount: Option[Int],
      resumeOffer: Option[InterruptedRun] = None
  ): List[Choice[MenuItem]] =
    val continueChoice = continueSessionCount.map(count =>
      Choice(
        MenuItem.ContinueSession,
        s"Continue a session from the last flow run ($count session(s))"
      )
    )
    val resumeChoice =
      resumeOffer.map(run => Choice(MenuItem.ResumeRun, resumeLabel(run)))
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

  /** `"Resume interrupted run — <flow>: <first ~40 chars of task>"`. The task
    * comes from a committed header and is often multi-line (`Main.promptTask`
    * reads multi-line), so it reaches the menu row through
    * [[TextUtil.onelinePreview]].
    */
  private def resumeLabel(run: InterruptedRun): String =
    val task = TextUtil.onelinePreview(run.userPrompt, 40)
    s"Resume interrupted run — ${run.flowName}: $task"
