package orca.shell.actions

import orca.settings.{SettingsFile, SettingsScope}
import orca.shell.create.CreateTier
import orca.testkit.TempDirs

class SettingsEditActionTest extends munit.FunSuite:

  private def withDirs(body: (os.Path, os.Path) => Unit): Unit =
    val workDir = TempDirs.dir()
    val globalPath = TempDirs.dir() / "settings.properties"
    body(workDir, globalPath)

  // --- pathFor ---

  test("pathFor: Project resolves to .orca/settings.properties under workDir"):
    withDirs: (workDir, globalPath) =>
      assertEquals(
        SettingsEditAction.pathFor(CreateTier.Project, workDir, globalPath),
        workDir / ".orca" / "settings.properties"
      )

  test("pathFor: Global resolves to the given global settings path as-is"):
    withDirs: (workDir, globalPath) =>
      assertEquals(
        SettingsEditAction.pathFor(CreateTier.Global, workDir, globalPath),
        globalPath
      )

  // --- ensureExists ---

  test(
    "ensureExists: Project creates the full commented template, and it parses clean"
  ):
    withDirs: (workDir, _) =>
      val path =
        SettingsEditAction.pathFor(CreateTier.Project, workDir, os.root)
      SettingsEditAction.ensureExists(CreateTier.Project, path, workDir)
      val content = os.read(path)
      assertEquals(content, SettingsEditAction.ProjectTemplate)
      assert(
        SettingsFile.parse(content, SettingsScope.Project).isRight,
        s"template must parse clean: $content"
      )

  test(
    "ensureExists: an untouched Project template leaves discovery armed " +
      "(its stack examples are commented, not live)"
  ):
    withDirs: (workDir, _) =>
      val path =
        SettingsEditAction.pathFor(CreateTier.Project, workDir, os.root)
      SettingsEditAction.ensureExists(CreateTier.Project, path, workDir)
      // A commented example does not count as "configured", so exiting the
      // editor without touching the template still lets the first flow run
      // auto-discover the stack.
      assert(!SettingsFile.hasStackLines(os.read(path)))

  test(
    "ensureExists: Project template guides both stack commands and role agents"
  ):
    withDirs: (workDir, _) =>
      val path =
        SettingsEditAction.pathFor(CreateTier.Project, workDir, os.root)
      SettingsEditAction.ensureExists(CreateTier.Project, path, workDir)
      val content = os.read(path)
      assert(content.contains("Stack commands"), content)
      assert(content.contains("Role agents"), content)
      assert(
        content.contains("one shell command per key; `off` disables the gate"),
        content
      )

  test("ensureExists: Global creates a header-only file with no role lines"):
    withDirs: (workDir, globalPath) =>
      SettingsEditAction.ensureExists(CreateTier.Global, globalPath, workDir)
      assert(os.exists(globalPath))
      assertEquals(
        ConfigAction.show(globalPath),
        Right(orca.settings.AgentSettings.empty)
      )

  test("ensureExists: never touches a file that already exists"):
    withDirs: (workDir, _) =>
      val path =
        SettingsEditAction.pathFor(CreateTier.Project, workDir, os.root)
      os.write.over(path, "codingAgent = codex\n", createFolders = true)
      SettingsEditAction.ensureExists(CreateTier.Project, path, workDir)
      assertEquals(os.read(path), "codingAgent = codex\n")

  // --- validate ---

  test("validate: Project — an absent file is valid"):
    withDirs: (workDir, _) =>
      assertEquals(
        SettingsEditAction.validate(CreateTier.Project, workDir, os.root),
        Right(())
      )

  test("validate: Project — a well-formed file is valid"):
    withDirs: (workDir, _) =>
      val path =
        SettingsEditAction.pathFor(CreateTier.Project, workDir, os.root)
      os.write.over(path, "codingAgent = codex\n", createFolders = true)
      assertEquals(
        SettingsEditAction.validate(CreateTier.Project, workDir, os.root),
        Right(())
      )

  test("validate: Project — a malformed file names the parse error"):
    withDirs: (workDir, _) =>
      val path =
        SettingsEditAction.pathFor(CreateTier.Project, workDir, os.root)
      os.write.over(path, "not a valid line\n", createFolders = true)
      assertEquals(
        SettingsEditAction.validate(CreateTier.Project, workDir, os.root),
        Left(
          "the project settings file is malformed — line 1: `not a valid line` is not a `#` comment and has no `=` — expected `key = value`"
        )
      )

  test("validate: Global — a malformed file names the parse error"):
    withDirs: (workDir, globalPath) =>
      os.write.over(globalPath, "not a valid line\n", createFolders = true)
      assertEquals(
        SettingsEditAction.validate(CreateTier.Global, workDir, globalPath),
        Left(
          "the global settings file is malformed — line 1: `not a valid line` is not a `#` comment and has no `=` — expected `key = value`"
        )
      )
