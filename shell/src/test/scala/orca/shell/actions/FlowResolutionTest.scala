package orca.shell.actions

import orca.progress.FlowSource
import orca.shell.TestShellEnv
import orca.testkit.TempDirs

class FlowResolutionTest extends munit.FunSuite:

  test("resolve treats a token containing '/' as a path, reading it directly"):
    val workDir = TempDirs.dir()
    os.write(
      workDir / "sub" / "release.sc",
      "// Release notes.\nval x = 1",
      createFolders = true
    )

    val result =
      FlowResolution.resolve("sub/release.sc")(using TestShellEnv(workDir))

    assertEquals(result.map(_.path), Right(workDir / "sub" / "release.sc"))
    assertEquals(result.map(_.name), Right("release.sc"))
    assertEquals(result.map(_.description), Right(Some("Release notes.")))
    assertEquals(result.map(_.shadows), Right(Nil))
    assertEquals(
      result.map(_.source),
      Right(FlowSource.File((workDir / "sub" / "release.sc").toString))
    )

  test(
    "resolve treats an existing bare '.sc' file as a path, not a catalog name"
  ):
    val workDir = TempDirs.dir()
    os.write(workDir / "release.sc", "// Release notes.\nval x = 1")

    val result =
      FlowResolution.resolve("release.sc")(using TestShellEnv(workDir))

    assertEquals(result.map(_.path), Right(workDir / "release.sc"))

  test("resolve reports a not-found path token containing '/'"):
    val workDir = TempDirs.dir()

    assertEquals(
      FlowResolution.resolve("missing/release.sc")(using TestShellEnv(workDir)),
      Left("no such flow file: missing/release.sc")
    )

  test(
    "resolve falls through to the catalog for a bare name with no matching file"
  ):
    val workDir = TempDirs.dir()

    assertEquals(
      FlowResolution.resolve("orca-flow-resolution-test-no-such-flow")(using
        TestShellEnv(workDir)
      ),
      Left(
        "no flow named 'orca-flow-resolution-test-no-such-flow' found in the catalog"
      )
    )

  test(
    "resolve's not-found error lists near-matches when a catalog name is a close typo"
  ):
    val workDir = TempDirs.dir()
    os.write(
      workDir / ".orca" / "flows" / "release-notes.sc",
      "// Release notes.\nval x = 1",
      createFolders = true
    )

    assertEquals(
      FlowResolution.resolve("relase-notes")(using TestShellEnv(workDir)),
      Left("no flow named 'relase-notes'; did you mean: release-notes.sc?")
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

    val result = FlowResolution.resolve(name)(using TestShellEnv(workDir))

    assertEquals(result.map(_.name), Right(s"$name.sc"))
    assertEquals(result.map(_.source), Right(FlowSource.Catalog(s"$name.sc")))
    assertEquals(
      result.map(_.path),
      Right(workDir / ".orca" / "flows" / s"$name.sc")
    )

  test("resolveRecorded never reads a catalog name as a path"):
    val workDir = TempDirs.dir()
    os.write(workDir / "x.sc", "val x = 1")
    assert(
      FlowResolution
        .resolveRecorded(FlowSource.Catalog("./x.sc"))(using
          TestShellEnv(workDir)
        )
        .isLeft
    )

  test("recordedFile refuses a relative path"):
    assertEquals(FlowResolution.recordedFile("scratch/x.sc"), None)

  test("recordedFile refuses a path that isn't a .sc script"):
    assertEquals(FlowResolution.recordedFile("/home/u/x.txt"), None)

  test("recordedFile refuses a path that doesn't print as stored"):
    // A right-to-left override makes the printed path read differently.
    assertEquals(FlowResolution.recordedFile("/home/u/x\u202E.sc"), None)

  test("recordedFile refuses a path that isn't normalised"):
    assertEquals(FlowResolution.recordedFile("/home/u/a/../x.sc"), None)
