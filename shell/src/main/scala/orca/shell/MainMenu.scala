package orca.shell

import orca.shell.ui.Choice

/** Main menu selection (ADR 0021 §3, `RediscoverStack` added §8). */
enum MenuItem:
  case RunFlow, ViewFlow, EditFlow, CreateFlow, ContinueSession, Reconfigure,
    RediscoverStack, Exit

object MainMenu:

  /** Fixed ADR §3 order; `continueDisabledReason` non-None renders the item
    * disabled, dropping `newestRunSessionCount` from its label — the count is
    * only meaningful once there's a run to report it for. Enabled, the label
    * names the newest run's own session count (the picker below it still lists
    * every run's sessions, older ones included).
    */
  def choices(
      continueDisabledReason: Option[String],
      newestRunSessionCount: Int
  ): List[Choice[MenuItem]] =
    val continueLabel = continueDisabledReason.fold(
      s"Continue a session from the last flow run ($newestRunSessionCount session(s))"
    )(_ => "Continue a session")
    List(
      Choice(MenuItem.RunFlow, "Run a flow"),
      Choice(MenuItem.ViewFlow, "View a flow"),
      Choice(MenuItem.EditFlow, "Edit a flow"),
      Choice(MenuItem.CreateFlow, "Create a new flow"),
      Choice(
        MenuItem.ContinueSession,
        continueLabel,
        disabledReason = continueDisabledReason
      ),
      Choice(MenuItem.Reconfigure, "Re-configure"),
      Choice(
        MenuItem.RediscoverStack,
        "Re-discover project stack settings"
      ),
      Choice(MenuItem.Exit, "Exit")
    )
