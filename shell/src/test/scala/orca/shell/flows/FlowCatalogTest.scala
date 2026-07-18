package orca.shell.flows

import orca.testkit.TempDirs

class FlowCatalogTest extends munit.FunSuite:

  private def flow(dir: os.Path, name: String, contents: String): Unit =
    os.write(dir / name, contents, createFolders = true)

  test(
    "a name present in all three tiers: project wins, shadows global and built-in"
  ):
    val project = TempDirs.dir()
    val global = TempDirs.dir()
    val builtIn = TempDirs.dir()
    flow(project, "release.sc", "// Project version.\nval x = 1")
    flow(global, "release.sc", "// Global version.\nval x = 1")
    flow(builtIn, "release.sc", "// Built-in version.\nval x = 1")

    val result = FlowCatalog.list(project, global, builtIn)

    assertEquals(result.map(_.name), List("release.sc"))
    val f = result.head
    assertEquals(f.origin, FlowOrigin.Project)
    assertEquals(f.path, project / "release.sc")
    assertEquals(f.description, Some("Project version."))
    assertEquals(f.shadows, List(FlowOrigin.Global, FlowOrigin.BuiltIn))

  test("a global-only flow has no shadows and origin Global"):
    val project = TempDirs.dir()
    val global = TempDirs.dir()
    val builtIn = TempDirs.dir()
    flow(global, "epic.sc", "// Run an epic.\nval x = 1")

    val result = FlowCatalog.list(project, global, builtIn)

    assertEquals(result.length, 1)
    assertEquals(result.head.origin, FlowOrigin.Global)
    assertEquals(result.head.shadows, Nil)

  test("a built-in-only flow has no shadows and origin BuiltIn"):
    val project = TempDirs.dir()
    val global = TempDirs.dir()
    val builtIn = TempDirs.dir()
    flow(builtIn, "issue-pr.sc", "// Turn an issue into a PR.\nval x = 1")

    val result = FlowCatalog.list(project, global, builtIn)

    assertEquals(result.length, 1)
    assertEquals(result.head.origin, FlowOrigin.BuiltIn)
    assertEquals(result.head.shadows, Nil)

  test("a non-.sc file in a tier dir is ignored"):
    val project = TempDirs.dir()
    val global = TempDirs.dir()
    val builtIn = TempDirs.dir()
    flow(project, "README.md", "not a flow")
    flow(project, "release.sc", "// Project version.\nval x = 1")

    val result = FlowCatalog.list(project, global, builtIn)

    assertEquals(result.map(_.name), List("release.sc"))

  test("a missing tier directory is tolerated"):
    val project = TempDirs.dir()
    val global = TempDirs.dir() / "does-not-exist"
    val builtIn = TempDirs.dir() / "does-not-exist-either"
    flow(project, "release.sc", "// Project version.\nval x = 1")

    val result = FlowCatalog.list(project, global, builtIn)

    assertEquals(result.map(_.name), List("release.sc"))

  test("entries are sorted by name"):
    val project = TempDirs.dir()
    val global = TempDirs.dir()
    val builtIn = TempDirs.dir()
    flow(project, "zeta.sc", "// Zeta.\nval x = 1")
    flow(project, "alpha.sc", "// Alpha.\nval x = 1")

    val result = FlowCatalog.list(project, global, builtIn)

    assertEquals(result.map(_.name), List("alpha.sc", "zeta.sc"))

  test("a symlinked .sc file in a tier dir is excluded from the catalog"):
    val project = TempDirs.dir()
    val global = TempDirs.dir()
    val builtIn = TempDirs.dir()
    val outsideTarget = TempDirs.dir() / "outside.sc"
    os.write(outsideTarget, "// Outside.\nval x = 1")
    os.symlink(project / "release.sc", outsideTarget)

    val result = FlowCatalog.list(project, global, builtIn)

    assertEquals(result, Nil)

  test("a flow with no leading description is listed with description None"):
    val project = TempDirs.dir()
    val global = TempDirs.dir()
    val builtIn = TempDirs.dir()
    flow(project, "bare.sc", "val x = 1")

    val result = FlowCatalog.list(project, global, builtIn)

    assertEquals(result.head.description, None)
