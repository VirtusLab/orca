package orca.shell.actions

import orca.testkit.TempDirs

class ConfigSummaryTest extends munit.FunSuite:

  private def withDirs(body: (os.Path, os.Path) => Unit): Unit =
    val globalDir = TempDirs.dir()
    val workDir = TempDirs.dir()
    body(globalDir / "settings.properties", workDir)

  // --- agentsLine ---

  test(
    "agentsLine: no global and no project file — every role defaults to claude"
  ):
    withDirs: (globalPath, workDir) =>
      assertEquals(
        ConfigSummary.agentsLine(globalPath, workDir),
        "agents: planning=claude, coding=claude, review=claude"
      )

  test("agentsLine: global-only settings render for every role"):
    withDirs: (globalPath, workDir) =>
      os.write(
        globalPath,
        "planningAgent = claude:fable\ncodingAgent = claude:opus\nreviewAgent = claude:opus\n"
      )
      assertEquals(
        ConfigSummary.agentsLine(globalPath, workDir),
        "agents: planning=claude:fable, coding=claude:opus, review=claude:opus"
      )

  test("agentsLine: a project override wins for just that role"):
    withDirs: (globalPath, workDir) =>
      os.write(
        globalPath,
        "planningAgent = claude:fable\ncodingAgent = claude:opus\nreviewAgent = claude:opus\n"
      )
      os.makeDir.all(workDir / ".orca")
      os.write(
        workDir / ".orca" / "settings.properties",
        "codingAgent = codex\n"
      )
      assertEquals(
        ConfigSummary.agentsLine(globalPath, workDir),
        "agents: planning=claude:fable, coding=codex, review=claude:opus"
      )

  test("agentsLine: a malformed global file renders a warning, not a crash"):
    withDirs: (globalPath, workDir) =>
      os.write(globalPath, "not a valid line\n")
      assert(
        ConfigSummary.agentsLine(globalPath, workDir).startsWith("agents: "),
        ConfigSummary.agentsLine(globalPath, workDir)
      )
      assert(
        ConfigSummary
          .agentsLine(globalPath, workDir)
          .contains("global settings file is malformed")
      )

  test("agentsLine: a malformed project file renders a warning, not a crash"):
    withDirs: (globalPath, workDir) =>
      os.makeDir.all(workDir / ".orca")
      os.write(workDir / ".orca" / "settings.properties", "not a valid line\n")
      assert(
        ConfigSummary
          .agentsLine(globalPath, workDir)
          .contains("project settings file is malformed")
      )

  // --- stackLine ---

  test("stackLine: no project settings file yet"):
    withDirs: (_, workDir) =>
      assertEquals(
        ConfigSummary.stackLine(workDir),
        "stack: not discovered yet — detected on the first flow run"
      )

  test(
    "stackLine: a settings file with no stack lines reads the same as absent"
  ):
    withDirs: (_, workDir) =>
      os.makeDir.all(workDir / ".orca")
      os.write(
        workDir / ".orca" / "settings.properties",
        "codingAgent = codex\n"
      )
      assertEquals(
        ConfigSummary.stackLine(workDir),
        "stack: not discovered yet — detected on the first flow run"
      )

  test("stackLine: every key set renders each command"):
    withDirs: (_, workDir) =>
      os.makeDir.all(workDir / ".orca")
      os.write(
        workDir / ".orca" / "settings.properties",
        "format = cargo fmt\nlint = cargo check --tests\ntest = cargo test\n"
      )
      assertEquals(
        ConfigSummary.stackLine(workDir),
        "stack: format=cargo fmt, lint=cargo check --tests, test=cargo test"
      )

  test("stackLine: a key absent from an otherwise-live file renders off"):
    withDirs: (_, workDir) =>
      os.makeDir.all(workDir / ".orca")
      os.write(
        workDir / ".orca" / "settings.properties",
        "format = cargo fmt\nlint = cargo check --tests\n"
      )
      assertEquals(
        ConfigSummary.stackLine(workDir),
        "stack: format=cargo fmt, lint=cargo check --tests, test=off"
      )

  test("stackLine: a key explicitly disabled with `off` also renders off"):
    withDirs: (_, workDir) =>
      os.makeDir.all(workDir / ".orca")
      os.write(
        workDir / ".orca" / "settings.properties",
        "format = cargo fmt\nlint = cargo check --tests\ntest = off\n"
      )
      assertEquals(
        ConfigSummary.stackLine(workDir),
        "stack: format=cargo fmt, lint=cargo check --tests, test=off"
      )

  test("stackLine: a malformed settings file renders a warning, not a crash"):
    withDirs: (_, workDir) =>
      os.makeDir.all(workDir / ".orca")
      os.write(
        workDir / ".orca" / "settings.properties",
        "format = cargo fmt\nnotAKey = whatever\n"
      )
      assert(
        ConfigSummary.stackLine(workDir).contains("invalid settings"),
        ConfigSummary.stackLine(workDir)
      )
