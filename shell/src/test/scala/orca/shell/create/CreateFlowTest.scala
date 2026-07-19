package orca.shell.create

import orca.agents.BackendTag
import orca.testkit.TempDirs

class CreateFlowTest extends munit.FunSuite:

  private val resourcePrefix = "/orca/shell/api/"

  private def resourceText(name: String): String =
    val stream = getClass.getResourceAsStream(resourcePrefix + name)
    assert(stream != null, s"missing resource $name")
    try
      new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()

  // --- extractApiMaterial ---

  test("extractApiMaterial writes the three bundled files, matching resources"):
    val target = TempDirs.dir()
    val dir = CreateFlow.extractApiMaterial(target, "0.0.18")
    assertEquals(dir, target / "orca-api-0.0.18")
    List("README.md", "implement.sc", "implement-interactive.sc").foreach:
      name => assertEquals(os.read(dir / name), resourceText(name))

  test(
    "extractApiMaterial is idempotent — a second call leaves files unchanged"
  ):
    val target = TempDirs.dir()
    val dir = CreateFlow.extractApiMaterial(target, "0.0.18")
    val mtimesBefore = os.list(dir).map(p => p.last -> os.mtime(p)).toMap

    val dirAgain = CreateFlow.extractApiMaterial(target, "0.0.18")

    assertEquals(dirAgain, dir)
    val mtimesAfter = os.list(dir).map(p => p.last -> os.mtime(p)).toMap
    assertEquals(mtimesAfter, mtimesBefore)

  test("extractApiMaterial self-heals a half-populated leftover dir"):
    val target = TempDirs.dir()
    val dir = target / "orca-api-0.0.18"
    os.makeDir.all(dir)
    os.write(dir / "README.md", "stale-partial-content")

    val result = CreateFlow.extractApiMaterial(target, "0.0.18")

    assertEquals(result, dir)
    assertEquals(os.read(dir / "README.md"), resourceText("README.md"))
    assertEquals(
      os.read(dir / "implement.sc"),
      resourceText("implement.sc")
    )

  // --- initialPrompt ---

  private val targetPath = os.root / "work" / ".orca" / "flows" / "new.sc"
  private val apiDir = os.root / "work" / ".orca" / "cache" / "orca-api-0.0.18"

  private def prompt: String =
    CreateFlow.initialPrompt(
      "sync issues nightly",
      targetPath,
      apiDir,
      "0.0.18"
    )

  test("initialPrompt states the goal and the target path"):
    assert(prompt.contains("sync issues nightly"))
    assert(prompt.contains(targetPath.toString))

  test("initialPrompt states the verbatim version-pinned header"):
    assert(prompt.contains("//> using scala 3.8.4"))
    assert(prompt.contains("""//> using dep "org.virtuslab::orca:0.0.18""""))
    assert(prompt.contains("//> using jvm 21"))

  test("initialPrompt points at the extracted README and both examples"):
    assert(prompt.contains((apiDir / "README.md").toString))
    assert(prompt.contains((apiDir / "implement.sc").toString))
    assert(prompt.contains((apiDir / "implement-interactive.sc").toString))

  test("initialPrompt states the compile-check line"):
    assert(prompt.contains(s"scala-cli compile $targetPath"))

  test("initialPrompt states the runtime-vs-compile-time rules caveat"):
    assert(prompt.contains("enforced at runtime"))

  test("initialPrompt (release version) has no ivy2Local repository line"):
    assert(!prompt.contains("ivy2Local"))

  test(
    "initialPrompt (dev version) injects ivy2Local right after the dep pin, so the compile hint stays honest"
  ):
    val devPrompt =
      CreateFlow.initialPrompt("sync issues nightly", targetPath, apiDir, "dev")
    val depLineIdx = devPrompt.linesIterator.indexWhere(
      _.contains("""//> using dep "org.virtuslab::orca:dev"""")
    )
    assert(depLineIdx >= 0, "expected a using-dep line")
    assertEquals(
      devPrompt.linesIterator.toList(depLineIdx + 1),
      "//> using repository ivy2Local"
    )

  test("initialPrompt's last-resort line is the tag-pinned raw README URL"):
    assert(
      prompt.contains(
        "https://raw.githubusercontent.com/VirtusLab/orca/v0.0.18/README.md"
      )
    )

  // --- harnessArgv ---

  test("harnessArgv: claude takes the prompt as a positional argument"):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.ClaudeCode, "do the thing"),
      HarnessLaunch(Seq("claude", "do the thing"), None)
    )

  test("harnessArgv: codex takes the prompt as a positional argument"):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Codex, "do the thing"),
      HarnessLaunch(Seq("codex", "do the thing"), None)
    )

  test("harnessArgv: pi takes the prompt as a positional argument"):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Pi, "do the thing"),
      HarnessLaunch(Seq("pi", "do the thing"), None)
    )

  test("harnessArgv: gemini uses -i/--prompt-interactive"):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Gemini, "do the thing"),
      HarnessLaunch(Seq("gemini", "-i", "do the thing"), None)
    )

  test(
    "harnessArgv: opencode launches bare with the prompt as a paste-fallback (--prompt only prefills, doesn't submit)"
  ):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Opencode, "do the thing"),
      HarnessLaunch(Seq("opencode"), Some("do the thing"))
    )

  // --- resolveTarget / prepareTarget ---

  test("normalizedFileName adds a .sc suffix when missing"):
    assertEquals(CreateFlow.normalizedFileName("my-flow"), "my-flow.sc")

  test("normalizedFileName leaves an existing .sc suffix alone"):
    assertEquals(CreateFlow.normalizedFileName("my-flow.sc"), "my-flow.sc")

  test("resolveTarget (Project): saves under .orca/flows, cwd is workDir"):
    val workDir = os.root / "repo"
    val globalFlows = os.root / "home" / "u" / ".config" / "orca" / "flows"
    assertEquals(
      CreateFlow.resolveTarget(
        CreateTier.Project,
        "my-flow",
        workDir,
        globalFlows
      ),
      CreateTarget(workDir / ".orca" / "flows" / "my-flow.sc", workDir)
    )

  test(
    "resolveTarget (Global): saves under the global flows dir, cwd is its parent"
  ):
    val workDir = os.root / "repo"
    val globalFlows = os.root / "home" / "u" / ".config" / "orca" / "flows"
    assertEquals(
      CreateFlow.resolveTarget(
        CreateTier.Global,
        "my-flow",
        workDir,
        globalFlows
      ),
      CreateTarget(
        globalFlows / "my-flow.sc",
        os.root / "home" / "u" / ".config" / "orca"
      )
    )

  test("prepareTarget (Project) ensures .orca/flows/ via OrcaDir.ensureFlows"):
    val workDir = TempDirs.dir()
    val result =
      CreateFlow.prepareTarget(
        CreateTier.Project,
        "my-flow",
        workDir,
        TempDirs.dir()
      )
    assertEquals(
      result,
      Right(CreateTarget(workDir / ".orca" / "flows" / "my-flow.sc", workDir))
    )
    assert(os.isDir(workDir / ".orca" / "flows"))

  test("prepareTarget (Global) ensures the global flows dir exists"):
    val globalFlows = TempDirs.dir() / "flows"
    val result = CreateFlow.prepareTarget(
      CreateTier.Global,
      "my-flow",
      TempDirs.dir(),
      globalFlows
    )
    assertEquals(
      result,
      Right(CreateTarget(globalFlows / "my-flow.sc", globalFlows / os.up))
    )
    assert(os.isDir(globalFlows))

  test("prepareTarget refuses a filename collision"):
    val workDir = TempDirs.dir()
    os.write(
      workDir / ".orca" / "flows" / "my-flow.sc",
      "// existing\n",
      createFolders = true
    )
    val result =
      CreateFlow.prepareTarget(
        CreateTier.Project,
        "my-flow",
        workDir,
        TempDirs.dir()
      )
    result match
      case Left(message) => assert(message.contains("already exists"))
      case Right(path) =>
        fail(s"expected a collision refusal, got Right($path)")
