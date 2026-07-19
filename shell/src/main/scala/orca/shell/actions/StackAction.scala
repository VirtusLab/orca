package orca.shell.actions

import orca.OrcaDir
import orca.StackSettings
import orca.settings.{SettingsFile, SettingsScope}
import orca.shell.ui.ShellOutput

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

  /** The one-line explanation shown when there's nothing to clear — an absent
    * settings file, or one with no live stack lines.
    */
  val noSettingsMessage =
    "no stack settings to clear (discovery runs on the next flow)"

  /** The confirm prompt shown before clearing a live stack settings block —
    * identical wording on the interactive menu and the CLI's `--yes`-less
    * terminal path.
    */
  val clearConfirmPrompt =
    "Clear discovered stack settings so the next flow run re-detects them?"

  /** Prints the current [[StackStatus.Present]] stack settings, then — when
    * `confirm()` returns true — clears them and reports the clear. The single
    * confirm-driven clear both fronts share (ADR 0021 §8): the interactive menu
    * passes a `ui.confirm`, the CLI passes `() => true` for `--yes` or its own
    * `ui.confirm` for the terminal path. Rendering always happens (even when
    * `confirm()` is false) so the CLI's non-interactive refusal still shows
    * what it would have cleared.
    */
  def clearIfConfirmed(
      workDir: os.Path,
      stack: StackSettings,
      content: String,
      confirm: () => Boolean
  ): Unit =
    ShellOutput.info("Current stack settings:")
    println(renderStackSettings(stack))
    if confirm() then
      clear(workDir, content)
      ShellOutput.info(
        "cleared — the next flow run will re-discover format/lint/test"
      )

  /** ` format: <cmd>` per line for each non-empty [[StackSettings]] key, in
    * format/lint/test order — display only for [[clearIfConfirmed]]'s confirm
    * prompt; a key with only demoted/unset (commented) lines and no live
    * command shows nothing here even though it still counts for
    * [[SettingsFile.hasStackLines]].
    */
  def renderStackSettings(stack: StackSettings): String =
    val rows =
      List("format" -> stack.format, "lint" -> stack.lint, "test" -> stack.test)
        .flatMap((key, commands) => commands.map(cmd => s"  $key: $cmd"))
    if rows.isEmpty then
      "  (no live commands — only commented-out/unset stack lines on file)"
    else rows.mkString("\n")
