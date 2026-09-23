package orca.shell.actions

import orca.settings.{SettingsFile, SettingsScope}
import orca.shell.{ShellEnv, TestShellEnv, Tier}

class SettingsEditActionTest extends munit.FunSuite:

  /** Runs `body` over a fresh [[TestShellEnv]]. */
  private def withEnv(body: ShellEnv ?=> Unit): Unit =
    body(using TestShellEnv())

  // --- ensureExists ---

  test(
    "ensureExists: Project creates the full commented template, and it parses clean"
  ):
    withEnv:
      SettingsEditAction.ensureExists(Tier.Project)
      val content = os.read(Tier.Project.settingsPath)
      assertEquals(content, SettingsEditAction.ProjectTemplate)
      assert(
        SettingsFile.parse(content, SettingsScope.Project).isRight,
        s"template must parse clean: $content"
      )

  test(
    "ensureExists: an untouched Project template leaves discovery armed " +
      "(its stack examples are commented, not live)"
  ):
    withEnv:
      SettingsEditAction.ensureExists(Tier.Project)
      // A commented example does not count as "configured", so exiting the
      // editor without touching the template still lets the first flow run
      // auto-discover the stack.
      assertEquals(
        SettingsFile
          .parse(os.read(Tier.Project.settingsPath), SettingsScope.Project)
          .map(_.stack),
        Right(None)
      )

  test(
    "ensureExists: Project template guides both stack commands and role agents"
  ):
    withEnv:
      SettingsEditAction.ensureExists(Tier.Project)
      val content = os.read(Tier.Project.settingsPath)
      assert(content.contains("Stack commands"), content)
      assert(content.contains("Role agents"), content)
      assert(
        content.contains("one shell command per key; `off` disables the gate"),
        content
      )

  test("ensureExists: Global creates a header-only file with no role lines"):
    withEnv:
      SettingsEditAction.ensureExists(Tier.Global)
      assert(os.exists(Tier.Global.settingsPath))
      assertEquals(
        ConfigAction.show(Tier.Global.settingsPath),
        Right(orca.settings.AgentSettings.empty)
      )

  test("ensureExists: never touches a file that already exists"):
    withEnv:
      val path = Tier.Project.settingsPath
      os.write.over(path, "codingAgent = codex\n", createFolders = true)
      SettingsEditAction.ensureExists(Tier.Project)
      assertEquals(os.read(path), "codingAgent = codex\n")

  // --- validate ---

  test("validate: Project — an absent file is valid"):
    withEnv:
      assertEquals(SettingsEditAction.validate(Tier.Project), Right(()))

  test("validate: Project — a well-formed file is valid"):
    withEnv:
      os.write.over(
        Tier.Project.settingsPath,
        "codingAgent = codex\n",
        createFolders = true
      )
      assertEquals(SettingsEditAction.validate(Tier.Project), Right(()))

  test("validate: Project — a malformed file names the parse error"):
    withEnv:
      os.write.over(
        Tier.Project.settingsPath,
        "not a valid line\n",
        createFolders = true
      )
      assertEquals(
        SettingsEditAction.validate(Tier.Project),
        Left(
          "the project settings file is malformed — line 1: `not a valid line` is not a `#` comment and has no `=` — expected `key = value`"
        )
      )

  test("validate: Global — a malformed file names the parse error"):
    withEnv:
      os.write.over(
        Tier.Global.settingsPath,
        "not a valid line\n",
        createFolders = true
      )
      assertEquals(
        SettingsEditAction.validate(Tier.Global),
        Left(
          "the global settings file is malformed — line 1: `not a valid line` is not a `#` comment and has no `=` — expected `key = value`"
        )
      )
