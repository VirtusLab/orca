package orca.shell.actions

import orca.OrcaDir
import orca.settings.{AgentSettings, SettingsFile}
import orca.shell.create.CreateTier
import ox.discard

/** Hand-edits the project or global settings file directly (ADR 0021 §4/§10) —
  * the tier-scoped counterpart to `Main.editFlow`/[[EditAction]]: this object
  * resolves the path and prepares/validates the file; the actual editor spawn
  * is [[EditAction.editInPlace]], shared with "Edit a flow".
  */
private[shell] object SettingsEditAction:

  /** `tier`'s settings file path — `.orca/settings.properties` under `workDir`
    * for [[CreateTier.Project]], `globalSettingsPath` itself for
    * [[CreateTier.Global]].
    */
  def pathFor(
      tier: CreateTier,
      workDir: os.Path,
      globalSettingsPath: os.Path
  ): os.Path =
    tier match
      case CreateTier.Project => OrcaDir.settingsPath(workDir)
      case CreateTier.Global  => globalSettingsPath

  /** Creates `path` from its tier's standard template if it doesn't already
    * exist yet — never touches a present file, malformed or not, since the
    * editor is about to give the user a chance to fix it themselves. Global:
    * [[ConfigAction.set]]'s own fresh-render write path with no roles set
    * ([[SettingsFile.renderGlobal]]) — the canonical write already used by the
    * wizard and `orca config`. Project: [[SettingsFile.Header]] with an empty
    * body ([[SettingsFile.render]] of no entries) — deliberately no stack
    * lines, so discovery stays armed for the next flow run — guarded by
    * [[OrcaDir]] the same way every other `.orca` write is.
    */
  def ensureExists(tier: CreateTier, path: os.Path, workDir: os.Path): Unit =
    tier match
      case CreateTier.Global =>
        if !os.exists(path) then ConfigAction.set(path, AgentSettings.empty)
      case CreateTier.Project =>
        OrcaDir.assertNoOrcaSymlinks(workDir, path)
        if !os.exists(path) then
          OrcaDir.ensureRoot(workDir).discard
          os.write.over(path, SettingsFile.render(Nil), createFolders = true)

  /** Re-parses `tier`'s settings file after the editor exits, reusing
    * [[ConfigAction.show]]/[[ConfigAction.showProject]] so the malformed-file
    * wording can't drift from theirs. `Right(())` for a valid file, and also
    * for one the user deleted in the editor (absent parses the same as empty) —
    * only a present-but-malformed file is a `Left`.
    */
  def validate(
      tier: CreateTier,
      workDir: os.Path,
      globalSettingsPath: os.Path
  ): Either[String, Unit] =
    val result = tier match
      case CreateTier.Project => ConfigAction.showProject(workDir)
      case CreateTier.Global  => ConfigAction.show(globalSettingsPath)
    result.map(_ => ())
