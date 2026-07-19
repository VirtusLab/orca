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

  test("initialPrompt indents a multi-line goal as its own block"):
    val multilineGoal = "sync issues nightly\nand also close stale ones"
    val text = CreateFlow.initialPrompt(
      multilineGoal,
      targetPath,
      apiDir,
      "0.0.18"
    )
    assert(text.contains("  sync issues nightly"))
    assert(text.contains("  and also close stale ones"))

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
      CreateFlow
        .harnessArgv(BackendTag.ClaudeCode, "do the thing", yolo = false),
      HarnessLaunch(Seq("claude", "do the thing"), None)
    )

  test("harnessArgv: codex takes the prompt as a positional argument"):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Codex, "do the thing", yolo = false),
      HarnessLaunch(Seq("codex", "do the thing"), None)
    )

  test("harnessArgv: pi takes the prompt as a positional argument"):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Pi, "do the thing", yolo = false),
      HarnessLaunch(Seq("pi", "do the thing"), None)
    )

  test("harnessArgv: gemini uses -i/--prompt-interactive"):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Gemini, "do the thing", yolo = false),
      HarnessLaunch(Seq("gemini", "-i", "do the thing"), None)
    )

  test(
    "harnessArgv: opencode launches bare with the prompt as a paste-fallback (--prompt only prefills, doesn't submit)"
  ):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Opencode, "do the thing", yolo = false),
      HarnessLaunch(Seq("opencode"), Some("do the thing"))
    )

  test("harnessArgv: yolo appends claude's --dangerously-skip-permissions"):
    assertEquals(
      CreateFlow
        .harnessArgv(BackendTag.ClaudeCode, "do the thing", yolo = true),
      HarnessLaunch(
        Seq("claude", "do the thing", "--dangerously-skip-permissions"),
        None
      )
    )

  test(
    "harnessArgv: yolo appends codex's --dangerously-bypass-approvals-and-sandbox"
  ):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Codex, "do the thing", yolo = true),
      HarnessLaunch(
        Seq(
          "codex",
          "do the thing",
          "--dangerously-bypass-approvals-and-sandbox"
        ),
        None
      )
    )

  test("harnessArgv: yolo appends gemini's --yolo"):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Gemini, "do the thing", yolo = true),
      HarnessLaunch(Seq("gemini", "-i", "do the thing", "--yolo"), None)
    )

  test("harnessArgv: yolo is a no-op for pi (no approval gate to bypass)"):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Pi, "do the thing", yolo = true),
      HarnessLaunch(Seq("pi", "do the thing"), None)
    )

  test(
    "harnessArgv: yolo is a no-op for opencode (no interactive-TUI flag exists)"
  ):
    assertEquals(
      CreateFlow.harnessArgv(BackendTag.Opencode, "do the thing", yolo = true),
      HarnessLaunch(Seq("opencode"), Some("do the thing"))
    )

  // --- yoloCaveat ---

  test("yoloCaveat is None when yolo wasn't requested, for every backend"):
    BackendTag.values.foreach: tag =>
      assertEquals(CreateFlow.yoloCaveat(tag, yolo = false), None)

  test("yoloCaveat is None for claude/codex/gemini when yolo was requested"):
    List(BackendTag.ClaudeCode, BackendTag.Codex, BackendTag.Gemini).foreach:
      tag => assertEquals(CreateFlow.yoloCaveat(tag, yolo = true), None)

  test("yoloCaveat notes pi has no approval gate to bypass"):
    assertEquals(
      CreateFlow.yoloCaveat(BackendTag.Pi, yolo = true),
      Some("pi has no approval gate to bypass — nothing to change.")
    )

  test("yoloCaveat notes opencode's approvals are config-only"):
    assertEquals(
      CreateFlow.yoloCaveat(BackendTag.Opencode, yolo = true),
      Some(
        "opencode has no interactive yolo flag — approvals are controlled by opencode.jsonc's `permission` field."
      )
    )

  // --- proposeFilename ---

  test(
    "proposeFilename slugs the first four meaningful words, dropping stopwords"
  ):
    assertEquals(
      CreateFlow.proposeFilename(
        "Implement a rate limiter for the login endpoint"
      ),
      "implement-rate-limiter-login.sc"
    )

  test("proposeFilename lowercases and strips punctuation"):
    assertEquals(
      CreateFlow.proposeFilename("Fix the bug: NPE on save!!!"),
      "fix-bug-npe-save.sc"
    )

  test("proposeFilename handles unicode letters as ordinary word characters"):
    assertEquals(
      CreateFlow.proposeFilename("Résumé café münchen naïve"),
      "résumé-café-münchen-naïve.sc"
    )

  test(
    "proposeFilename falls back to new-flow.sc when nothing survives filtering (all stopwords)"
  ):
    assertEquals(CreateFlow.proposeFilename("the a to for and"), "new-flow.sc")

  test("proposeFilename falls back to new-flow.sc on punctuation-only input"):
    assertEquals(CreateFlow.proposeFilename("!!! ??? ..."), "new-flow.sc")

  test("proposeFilename falls back to new-flow.sc on empty input"):
    assertEquals(CreateFlow.proposeFilename(""), "new-flow.sc")

  test("proposeFilename takes only the first four words, ignoring the rest"):
    assertEquals(
      CreateFlow.proposeFilename("one two three four five six seven"),
      "one-two-three-four.sc"
    )

  // --- slugArgv ---

  test("slugArgv: claude uses -p/--print"):
    assertEquals(
      CreateFlow.slugArgv(BackendTag.ClaudeCode, "suggest a name"),
      Seq("claude", "-p", "suggest a name")
    )

  test("slugArgv: codex uses the exec subcommand"):
    assertEquals(
      CreateFlow.slugArgv(BackendTag.Codex, "suggest a name"),
      Seq("codex", "exec", "suggest a name")
    )

  test("slugArgv: pi uses -p/--print"):
    assertEquals(
      CreateFlow.slugArgv(BackendTag.Pi, "suggest a name"),
      Seq("pi", "-p", "suggest a name")
    )

  test("slugArgv: gemini uses -p/--prompt (the non-interactive/headless flag)"):
    assertEquals(
      CreateFlow.slugArgv(BackendTag.Gemini, "suggest a name"),
      Seq("gemini", "-p", "suggest a name")
    )

  test("slugArgv: opencode uses the run subcommand"):
    assertEquals(
      CreateFlow.slugArgv(BackendTag.Opencode, "suggest a name"),
      Seq("opencode", "run", "suggest a name")
    )

  // --- slugPrompt ---

  test("slugPrompt states the goal and asks for only the filename"):
    val text = CreateFlow.slugPrompt("sync issues nightly")
    assert(text.contains("sync issues nightly"))
    assert(text.contains("Reply with ONLY the filename"))

  // --- sanitizeSlug ---

  test("sanitizeSlug kebab-cases junk (mixed case, punctuation, spaces)"):
    assertEquals(
      CreateFlow.sanitizeSlug("Sure! Here's one: My Cool Flow"),
      "sure-here-s-one-my-cool-flow.sc"
    )

  test("sanitizeSlug strips an existing .sc suffix before re-appending it"):
    assertEquals(CreateFlow.sanitizeSlug("my-cool-flow.sc"), "my-cool-flow.sc")

  test("sanitizeSlug falls back to new-flow.sc on an empty string"):
    assertEquals(CreateFlow.sanitizeSlug(""), "new-flow.sc")

  test("sanitizeSlug falls back to new-flow.sc on punctuation/whitespace only"):
    assertEquals(CreateFlow.sanitizeSlug("   !!! ... ???  "), "new-flow.sc")

  test("sanitizeSlug bounds an unreasonably long reply"):
    val long = ("word" * 30)
    val result = CreateFlow.sanitizeSlug(long)
    assert(result.stripSuffix(".sc").length <= 50, result)

  // --- suggestFilename ---

  test("suggestFilename sanitizes the harness's last non-blank output line"):
    val runner: (Seq[String], Long) => Option[String] =
      (_, _) => Some("Thinking...\n\nSure, how about: My Cool Flow\n")
    val result =
      CreateFlow.suggestFilename(
        BackendTag.ClaudeCode,
        "sync issues nightly",
        runner = runner
      )
    assertEquals(result, "sure-how-about-my-cool-flow.sc")

  test(
    "suggestFilename falls back to proposeFilename when the harness is unreachable/times out"
  ):
    val runner: (Seq[String], Long) => Option[String] = (_, _) => None
    val goal = "Implement a rate limiter for the login endpoint"
    assertEquals(
      CreateFlow.suggestFilename(BackendTag.ClaudeCode, goal, runner = runner),
      CreateFlow.proposeFilename(goal)
    )

  test(
    "suggestFilename falls back to proposeFilename when the harness reply sanitizes to nothing"
  ):
    val runner: (Seq[String], Long) => Option[String] =
      (_, _) => Some("   !!! ... ???  ")
    val goal = "Implement a rate limiter for the login endpoint"
    assertEquals(
      CreateFlow.suggestFilename(BackendTag.ClaudeCode, goal, runner = runner),
      CreateFlow.proposeFilename(goal)
    )

  test("suggestFilename passes slugArgv/slugPrompt through to the runner"):
    var seenArgv: Seq[String] = Nil
    val runner: (Seq[String], Long) => Option[String] =
      (argv, _) => { seenArgv = argv; Some("my-flow") }
    CreateFlow.suggestFilename(
      BackendTag.Gemini,
      "sync issues nightly",
      runner = runner
    )
    assertEquals(
      seenArgv,
      CreateFlow.slugArgv(
        BackendTag.Gemini,
        CreateFlow.slugPrompt("sync issues nightly")
      )
    )

  // --- forkFilenameDefault ---

  test("forkFilenameDefault strips .sc and appends -fork.sc"):
    assertEquals(
      CreateFlow.forkFilenameDefault("implement.sc"),
      "implement-fork.sc"
    )

  test("forkFilenameDefault appends -fork.sc even without a .sc source name"):
    assertEquals(
      CreateFlow.forkFilenameDefault("implement"),
      "implement-fork.sc"
    )

  // --- resolveForkSource ---

  test(
    "resolveForkSource returns the source path unchanged when it's already inside cwd"
  ):
    val cwd = TempDirs.dir()
    val source = cwd / ".orca" / "flows" / "implement.sc"
    os.write(source, "// a flow\n", createFolders = true)
    val apiDir = TempDirs.dir()
    assertEquals(
      CreateFlow.resolveForkSource(source, "implement.sc", cwd, apiDir),
      source
    )
    assert(!os.exists(apiDir / "implement.sc"), "should not have copied")

  test("resolveForkSource copies the source into apiDir when it's outside cwd"):
    val cwd = TempDirs.dir()
    val sourceDir = TempDirs.dir()
    val source = sourceDir / "implement.sc"
    os.write(source, "// a flow\n")
    val apiDir = TempDirs.dir()
    val resolved =
      CreateFlow.resolveForkSource(source, "implement.sc", cwd, apiDir)
    assertEquals(resolved, apiDir / "implement.sc")
    assertEquals(os.read(resolved), "// a flow\n")

  test("resolveForkSource doesn't re-copy when a copy already exists"):
    val cwd = TempDirs.dir()
    val sourceDir = TempDirs.dir()
    val source = sourceDir / "implement.sc"
    os.write(source, "// original\n")
    val apiDir = TempDirs.dir()
    os.write(apiDir / "implement.sc", "// pre-existing copy\n")

    val resolved =
      CreateFlow.resolveForkSource(source, "implement.sc", cwd, apiDir)

    assertEquals(resolved, apiDir / "implement.sc")
    assertEquals(os.read(resolved), "// pre-existing copy\n")

  // --- forkPrompt ---

  private def fork: String =
    CreateFlow.forkPrompt(
      "add a rate limit",
      targetPath / os.up / "implement.sc",
      targetPath,
      apiDir,
      "0.0.18"
    )

  test("forkPrompt states the source path, the changes, and the target path"):
    assert(fork.contains("add a rate limit"))
    assert(fork.contains(targetPath.toString))
    assert(fork.contains((targetPath / os.up / "implement.sc").toString))

  test("forkPrompt instructs copying the source before applying changes"):
    assert(fork.contains("First copy"))
    assert(fork.contains("verbatim"))

  test("forkPrompt points at the extracted README and both examples"):
    assert(fork.contains((apiDir / "README.md").toString))
    assert(fork.contains((apiDir / "implement.sc").toString))
    assert(fork.contains((apiDir / "implement-interactive.sc").toString))

  test("forkPrompt states the compile-check line"):
    assert(fork.contains(s"scala-cli compile $targetPath"))

  test("forkPrompt states the runtime-vs-compile-time rules caveat"):
    assert(fork.contains("enforced at runtime"))

  test("forkPrompt indents a multi-line change description as its own block"):
    val multilineChanges = "add a rate limit\nand log rejected requests"
    val text = CreateFlow.forkPrompt(
      multilineChanges,
      targetPath / os.up / "implement.sc",
      targetPath,
      apiDir,
      "0.0.18"
    )
    assert(text.contains("  add a rate limit"))
    assert(text.contains("  and log rejected requests"))

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
