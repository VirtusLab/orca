package orca.shell.actions

import orca.settings.{AgentSettings, SettingsFile, SettingsScope}
import orca.shell.ui.ShellOutput

/** Shows or writes the user-global settings file's role agents (ADR 0021 §4) —
  * the moved read/write half of what `Wizard.run`/`Wizard.write` used inline;
  * the wizard keeps its own role-selection prompting and calls [[set]] with the
  * result.
  */
private[shell] object ConfigAction:

  /** The global settings file's currently configured role agents —
    * [[AgentSettings.empty]] when the file doesn't exist. Left on a malformed
    * file, naming the parse error.
    */
  def show(globalSettingsPath: os.Path): Either[String, AgentSettings] =
    if !os.exists(globalSettingsPath) then Right(AgentSettings.empty)
    else
      SettingsFile
        .parse(os.read(globalSettingsPath), SettingsScope.UserGlobal)
        .map(_.agents)
        .left
        .map(error =>
          s"the global settings file is malformed — ${error.message}"
        )

  /** Writes `agents` to the global settings file. The rewrite strategy gates on
    * whether the existing content parses cleanly under
    * [[SettingsScope.UserGlobal]] — not merely on whether the file existed — so
    * a malformed file is rewritten from scratch exactly like an absent one,
    * instead of having [[SettingsFile.updateGlobal]] pass its unparseable lines
    * through unchanged: clean-parse → `updateGlobal` (preserves comments),
    * absent or malformed → [[SettingsFile.renderGlobal]] (full rewrite).
    */
  def set(globalSettingsPath: os.Path, agents: AgentSettings): Unit =
    val existingContent =
      Option.when(os.exists(globalSettingsPath))(os.read(globalSettingsPath))
    val parseable = existingContent
      .filter(text =>
        SettingsFile.parse(text, SettingsScope.UserGlobal).isRight
      )
    // A malformed file is replaced wholesale, discarding any hand-written
    // comments alongside the bad lines — say so, since the CLI's `config` and
    // the interactive Reconfigure path both reach here without a repair
    // flow's explicit confirm.
    if existingContent.nonEmpty && parseable.isEmpty then
      ShellOutput.info(
        s"The existing file at $globalSettingsPath did not parse; rewriting it " +
          "from scratch (previous contents, including comments, are replaced)."
      )
    val content = parseable
      .fold(SettingsFile.renderGlobal(agents))(
        SettingsFile.updateGlobal(_, agents)
      )
    os.write.over(globalSettingsPath, content, createFolders = true)
    ShellOutput.info(
      s"Settings written to $globalSettingsPath — hand-editable any time " +
        "(`harness[:model]`, e.g. `claude:sonnet`)."
    )
