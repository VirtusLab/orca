package orca.shell.flows

import orca.testkit.TempDirs

class FlowEditorTest extends munit.FunSuite:

  test("resolveEditor prefers VISUAL over EDITOR when both are set"):
    val env = Map("VISUAL" -> "code --wait", "EDITOR" -> "nano").get
    assertEquals(FlowEditor.resolveEditor(env), "code --wait")

  test("resolveEditor falls back to EDITOR when only it is set"):
    val env = Map("EDITOR" -> "nano").get
    assertEquals(FlowEditor.resolveEditor(env), "nano")

  test("resolveEditor falls back to VISUAL when only it is set"):
    val env = Map("VISUAL" -> "code --wait").get
    assertEquals(FlowEditor.resolveEditor(env), "code --wait")

  test("resolveEditor falls back to vi when neither is set"):
    assertEquals(FlowEditor.resolveEditor(Map.empty.get), "vi")

  test("editArgv wraps the editor in sh -c, passing the path as a single argument"):
    val path = os.root / "home" / "u" / "my flows" / "release.sc"
    assertEquals(
      FlowEditor.editArgv("code --wait", path),
      Seq("sh", "-c", """code --wait "$@"""", "code --wait", path.toString)
    )

  test("editArgv leaves a spaces-bearing path intact as one word"):
    val path = os.root / "a" / "b c" / "d.sc"
    val argv = FlowEditor.editArgv("vi", path)
    assertEquals(argv.last, path.toString)
    assertEquals(argv.length, 5)

  private def builtInFlow(dir: os.Path, name: String, contents: String): DiscoveredFlow =
    os.write(dir / name, contents, createFolders = true)
    DiscoveredFlow(name, description = None, origin = FlowOrigin.BuiltIn, path = dir / name, shadows = Nil)

  test("customizeTarget copies a built-in into the project tier via OrcaDir.ensureFlows"):
    val workDir = TempDirs.dir()
    val builtIn = TempDirs.dir()
    val flow = builtInFlow(builtIn, "release.sc", "// Release.\nval x = 1")

    val result = FlowEditor.customizeTarget(flow, FlowOrigin.Project, workDir, TempDirs.dir())

    val expected = workDir / ".orca" / "flows" / "release.sc"
    assertEquals(result, Right(expected))
    assertEquals(os.read(expected), "// Release.\nval x = 1")

  test("customizeTarget copies a built-in into the global tier"):
    val builtIn = TempDirs.dir()
    val globalFlows = TempDirs.dir() / "flows"
    val flow = builtInFlow(builtIn, "epic.sc", "// Epic.\nval x = 1")

    val result = FlowEditor.customizeTarget(flow, FlowOrigin.Global, TempDirs.dir(), globalFlows)

    assertEquals(result, Right(globalFlows / "epic.sc"))
    assertEquals(os.read(globalFlows / "epic.sc"), "// Epic.\nval x = 1")

  test("customizeTarget refuses to overwrite an existing file at the destination"):
    val workDir = TempDirs.dir()
    val builtIn = TempDirs.dir()
    val flow = builtInFlow(builtIn, "release.sc", "// Release.\nval x = 1")
    os.write(workDir / ".orca" / "flows" / "release.sc", "// Already customized.\n", createFolders = true)

    val result = FlowEditor.customizeTarget(flow, FlowOrigin.Project, workDir, TempDirs.dir())

    result match
      case Left(message) => assert(message.contains("already exists"), s"unexpected message: $message")
      case Right(path)    => fail(s"expected a collision refusal, got Right($path)")
