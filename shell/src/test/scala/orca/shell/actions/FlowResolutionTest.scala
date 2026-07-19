package orca.shell.actions

import orca.testkit.TempDirs

class FlowResolutionTest extends munit.FunSuite:

  test("resolve treats a token containing '/' as a path, reading it directly"):
    val workDir = TempDirs.dir()
    os.write(
      workDir / "sub" / "release.sc",
      "// Release notes.\nval x = 1",
      createFolders = true
    )

    val result = FlowResolution.resolve("sub/release.sc", workDir)

    assertEquals(result.map(_.path), Right(workDir / "sub" / "release.sc"))
    assertEquals(result.map(_.name), Right("release.sc"))
    assertEquals(result.map(_.description), Right(Some("Release notes.")))
    assertEquals(result.map(_.shadows), Right(Nil))

  test(
    "resolve treats an existing bare '.sc' file as a path, not a catalog name"
  ):
    val workDir = TempDirs.dir()
    os.write(workDir / "release.sc", "// Release notes.\nval x = 1")

    val result = FlowResolution.resolve("release.sc", workDir)

    assertEquals(result.map(_.path), Right(workDir / "release.sc"))

  test("resolve reports a not-found path token containing '/'"):
    val workDir = TempDirs.dir()

    assertEquals(
      FlowResolution.resolve("missing/release.sc", workDir),
      Left("no such flow file: missing/release.sc")
    )

  test(
    "resolve falls through to the catalog for a bare name with no matching file"
  ):
    val workDir = TempDirs.dir()

    assertEquals(
      FlowResolution.resolve("orca-flow-resolution-test-no-such-flow", workDir),
      Left(
        "no flow named 'orca-flow-resolution-test-no-such-flow' found in the catalog"
      )
    )

  test(
    "resolve finds a bare catalog name (no '.sc' suffix required) in the project tier"
  ):
    val workDir = TempDirs.dir()
    val name = "orca-flow-resolution-test-project-flow"
    os.write(
      workDir / ".orca" / "flows" / s"$name.sc",
      "// A project flow.\nval x = 1",
      createFolders = true
    )

    val result = FlowResolution.resolve(name, workDir)

    assertEquals(result.map(_.name), Right(s"$name.sc"))
    assertEquals(
      result.map(_.path),
      Right(workDir / ".orca" / "flows" / s"$name.sc")
    )
