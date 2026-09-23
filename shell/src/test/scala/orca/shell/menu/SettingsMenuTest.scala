package orca.shell.menu

import orca.settings.{SettingsFile, SettingsScope}
import orca.shell.{ShellEnv, TestShellEnv, Tier}
import orca.shell.actions.SettingsEditAction
import orca.shell.ui.UiOutcome
import orca.testkit.TempDirs

import MenuFixtures.{captured, withDumbTerminal}

class SettingsMenuTest extends munit.FunSuite:

  // --- editSettings ---
  //
  // `spawnEditor` is faked, so these tests never spawn a real editor: the fake
  // stands in for "the editor ran and exited", optionally rewriting the file
  // first to simulate what the user did inside it.

  test("editSettings: cancelling the tier prompt spawns no editor"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript = List(UiOutcome.Cancelled))
      SettingsMenu.editSettings(ui, terminal, MenuFixtures.noEditor)(using
        TestShellEnv()
      )

  test(
    "editSettings: Project — an absent file is created from the template before the editor opens it"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      val ui =
        FlowScriptedUi(selectScript = List(UiOutcome.Selected(Tier.Project)))
      var editedPath: Option[os.Path] = None
      SettingsMenu.editSettings(
        ui,
        terminal,
        (_, path) => { editedPath = Some(path); 0 }
      )(using TestShellEnv(workDir))
      val expected = workDir / ".orca" / "settings.properties"
      assertEquals(editedPath, Some(expected))
      assertEquals(os.read(expected), SettingsEditAction.ProjectTemplate)

  test(
    "editSettings: Global — an absent file is created from the template before the editor opens it"
  ):
    withDumbTerminal: terminal =>
      given env: ShellEnv = TestShellEnv()
      val ui =
        FlowScriptedUi(selectScript = List(UiOutcome.Selected(Tier.Global)))
      SettingsMenu.editSettings(ui, terminal, (_, _) => 0)
      assert(os.exists(env.configHome.settings))

  test(
    "editSettings: a valid edit reprints the config summary"
  ):
    withDumbTerminal: terminal =>
      val ui =
        FlowScriptedUi(selectScript = List(UiOutcome.Selected(Tier.Global)))
      val out = captured(
        SettingsMenu.editSettings(ui, terminal, (_, _) => 0)(using
          TestShellEnv()
        )
      )
      assert(out.contains("agents:"), out)
      assert(out.contains("stack:"), out)

  test(
    "editSettings: a malformed edit prints a warning instead of crashing, without reprinting the summary"
  ):
    withDumbTerminal: terminal =>
      val ui =
        FlowScriptedUi(selectScript = List(UiOutcome.Selected(Tier.Global)))
      val out = captured(
        SettingsMenu.editSettings(
          ui,
          terminal,
          (_, path) => {
            os.write.over(path, "not a valid line\n")
            0
          }
        )(using TestShellEnv())
      )
      assert(out.contains("malformed"), out)
      assert(!out.contains("agents:"), out)

  // --- rediscoverStack ---

  test(
    "rediscoverStack is a no-op, without creating .orca, when the settings file is absent"
  ):
    val dir = TempDirs.dir()
    val out =
      captured(
        SettingsMenu.rediscoverStack(ConfirmOnlyUi(UiOutcome.Cancelled))(using
          TestShellEnv(dir)
        )
      )
    assert(!os.exists(dir / ".orca"))
    assert(
      out.contains("no stack settings to clear"),
      s"should explain there's nothing to clear: $out"
    )

  test(
    "rediscoverStack is a no-op, leaving the file untouched, when it has no stack lines"
  ):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content =
      "# orca settings — edit freely, commit with the project.\ncodingAgent = codex\n"
    os.write.over(path, content)
    val out =
      captured(
        SettingsMenu.rediscoverStack(ConfirmOnlyUi(UiOutcome.Cancelled))(using
          TestShellEnv(dir)
        )
      )
    assertEquals(os.read(path), content)
    assert(
      out.contains("no stack settings to clear"),
      s"should explain there's nothing to clear: $out"
    )

  test("rediscoverStack aborts on a malformed settings file without writing"):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content = "format = cargo fmt\nnotAKey = whatever\n"
    os.write.over(path, content)
    val out =
      captured(
        SettingsMenu.rediscoverStack(ConfirmOnlyUi(UiOutcome.Cancelled))(using
          TestShellEnv(dir)
        )
      )
    assertEquals(os.read(path), content)
    assert(
      out.contains("invalid settings"),
      s"should abort with the parse error: $out"
    )

  test(
    "rediscoverStack strips stack lines and writes back when the user confirms"
  ):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content = SettingsFile.Header + "\n" +
      "format = cargo fmt\n" +
      "codingAgent = codex\n"
    os.write.over(path, content)
    SettingsMenu.rediscoverStack(ConfirmOnlyUi(UiOutcome.Selected(true)))(using
      TestShellEnv(dir)
    )
    val rewritten = os.read(path)
    assertEquals(rewritten, SettingsFile.stripStackLines(content))
    assertEquals(
      SettingsFile.parse(rewritten, SettingsScope.Project).map(_.stack),
      Right(None)
    )

  test("rediscoverStack leaves the file untouched when the user declines"):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content =
      "# orca settings — edit freely, commit with the project.\n" +
        "format = cargo fmt\n"
    os.write.over(path, content)
    SettingsMenu.rediscoverStack(ConfirmOnlyUi(UiOutcome.Selected(false)))(using
      TestShellEnv(dir)
    )
    assertEquals(os.read(path), content)

  // --- printConfigSummary ---

  test(
    "printConfigSummary prints the agents line then the stack line, both shell-voice"
  ):
    val out = captured(SettingsMenu.printConfigSummary(using TestShellEnv()))
    assertEquals(
      out.linesIterator.toList,
      List(
        "◆ agents: planning=claude, coding=claude, review=claude",
        "◆ stack: not discovered yet — detected on the first flow run"
      )
    )
