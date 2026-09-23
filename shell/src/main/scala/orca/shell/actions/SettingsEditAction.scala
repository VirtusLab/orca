package orca.shell.actions

import orca.OrcaDir
import orca.settings.{AgentSettings, SettingsFile}
import orca.shell.{ShellEnv, Tier}
import ox.discard

/** Hand-edits the project or global settings file directly (ADR 0021 §4/§10) —
  * the tier-scoped counterpart to `AuthoringMenu.editFlow`/[[EditAction]]: this
  * object prepares/validates the file ([[Tier.settingsPath]]); the actual
  * editor spawn is [[EditAction.editInPlace]], shared with "Edit a flow".
  */
private[shell] object SettingsEditAction:

  /** A fresh project settings file's full starter content:
    * [[SettingsFile.Header]] (documenting `off` and the re-discovery trigger)
    * plus every key commented out with a fill-in-blank example. Comments
    * configure nothing, so this template leaves auto-discovery armed exactly
    * like an absent file: an untouched exit still gets the stack discovered on
    * the next flow run. A user who fills in a real value un-comments it; one
    * who wants a gate off writes `off` instead of a command.
    */
  private[shell] val ProjectTemplate: String =
    SettingsFile.Header + "\n\n" +
      "# Stack commands — one shell command per key; repeat a key to append.\n" +
      "# format = cargo fmt\n" +
      "# lint = cargo check --tests\n" +
      "# test = cargo test\n" +
      "\n" +
      "# Role agents — harness[:model]; harness: claude|codex|opencode|pi|gemini.\n" +
      "# planningAgent = claude:fable\n" +
      "# codingAgent = claude:opus\n" +
      "# reviewAgent = claude:opus\n"

  /** Creates `tier`'s settings file from its standard template if it doesn't
    * already exist yet — never touches a present file, malformed or not, since
    * the editor is about to give the user a chance to fix it themselves.
    * Global: [[ConfigAction.set]]'s own fresh-render write path with no roles
    * set ([[SettingsFile.renderGlobal]]) — the canonical write already used by
    * the wizard and `orca config`. Project: [[ProjectTemplate]] — guarded by
    * [[OrcaDir]] the same way every other `.orca` write is.
    */
  def ensureExists(tier: Tier)(using env: ShellEnv): Unit =
    val path = tier.settingsPath
    tier match
      case Tier.Global =>
        if !os.exists(path) then ConfigAction.set(path, AgentSettings.empty)
      case Tier.Project =>
        OrcaDir.assertNoOrcaSymlinks(env.workDir, path)
        if !os.exists(path) then
          OrcaDir.ensureRoot(env.workDir).discard
          os.write(path, ProjectTemplate)

  /** Re-parses `tier`'s settings file after the editor exits, reusing
    * [[ConfigAction.show]]/[[ConfigAction.showProject]] so the malformed-file
    * wording can't drift from theirs. `Right(())` for a valid file, and also
    * for one the user deleted in the editor (absent parses the same as empty) —
    * only a present-but-malformed file is a `Left`.
    */
  def validate(tier: Tier)(using env: ShellEnv): Either[String, Unit] =
    val result = tier match
      case Tier.Project => ConfigAction.showProject(env.workDir)
      case Tier.Global  => ConfigAction.show(tier.settingsPath)
    result.map(_ => ())
