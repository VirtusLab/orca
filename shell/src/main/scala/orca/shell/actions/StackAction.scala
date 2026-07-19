package orca.shell.actions

import orca.OrcaDir
import orca.StackSettings
import orca.settings.{SettingsFile, SettingsScope}

import scala.util.control.NonFatal

/** The project settings file's discovered-stack state, as read by
  * [[StackAction.status]].
  */
private[shell] enum StackStatus:
  case NoSettings
  case NoStackLines
  case Present(stack: StackSettings, content: String)

/** "Re-discover project stack settings" (ADR 0021 §8/§4, feedback item 4) — the
  * moved read/write halves of `Main.rediscoverStack`; the confirm prompt and
  * rendering stay in `Main`.
  */
private[shell] object StackAction:

  /** Reads and guards the project settings file the same way
    * `Main.rediscoverStack` used to inline: a symlink guard
    * ([[OrcaDir.assertNoOrcaSymlinks]]) so this never creates `.orca`, then an
    * absent file or one with no stack lines already reported as a no-op, and a
    * malformed file reported as an error instead of surgically edited blind.
    */
  def status(workDir: os.Path): Either[String, StackStatus] =
    val path = OrcaDir.settingsPath(workDir)
    try
      OrcaDir.assertNoOrcaSymlinks(workDir, path)
      if !os.exists(path) then Right(StackStatus.NoSettings)
      else
        val content = os.read(path)
        if !SettingsFile.hasStackLines(content) then
          Right(StackStatus.NoStackLines)
        else
          SettingsFile.parse(content, SettingsScope.Project) match
            case Left(error) =>
              Left(s"invalid settings at $path: ${error.message}")
            case Right(parsed) =>
              Right(StackStatus.Present(parsed.stack, content))
    catch
      case NonFatal(e) =>
        Left(s"couldn't re-discover stack settings — ${e.getMessage}")

  /** Strips the stack lines out of `content` ([[SettingsFile.stripStackLines]])
    * and writes the result back to the project settings file — so the next flow
    * run's own `hasStackLines`-driven check re-triggers discovery.
    */
  def clear(workDir: os.Path, content: String): Unit =
    os.write.over(
      OrcaDir.settingsPath(workDir),
      SettingsFile.stripStackLines(content)
    )
