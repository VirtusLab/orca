package orca.shell

import orca.shell.ui.Choice

/** Main menu selection (ADR 0021 §3). */
enum MenuItem:
  case RunFlow, ViewFlow, EditFlow, CreateFlow, ContinueSession, Reconfigure, Exit

object MainMenu:

  /** Fixed ADR §3 order; `continueDisabledReason` non-None renders the item
    * disabled.
    */
  def choices(continueDisabledReason: Option[String]): List[Choice[MenuItem]] =
    List(
      Choice(MenuItem.RunFlow, "Run a flow"),
      Choice(MenuItem.ViewFlow, "View a flow"),
      Choice(MenuItem.EditFlow, "Edit a flow"),
      Choice(MenuItem.CreateFlow, "Create a new flow"),
      Choice(MenuItem.ContinueSession, "Continue a session", disabledReason = continueDisabledReason),
      Choice(MenuItem.Reconfigure, "Re-configure"),
      Choice(MenuItem.Exit, "Exit")
    )
