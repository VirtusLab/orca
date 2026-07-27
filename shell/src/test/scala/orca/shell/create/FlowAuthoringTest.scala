package orca.shell.create

import orca.agents.BackendTag
import orca.testkit.TempDirs

class FlowAuthoringTest extends munit.FunSuite:

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
    val dir = FlowAuthoring.extractApiMaterial(target, "0.0.18")
    assertEquals(dir, target / "orca-api-0.0.18")
    List("README.md", "implement.sc", "implement-interactive.sc").foreach:
      name => assertEquals(os.read(dir / name), resourceText(name))

  test(
    "extractApiMaterial is idempotent — a second call leaves files unchanged"
  ):
    val target = TempDirs.dir()
    val dir = FlowAuthoring.extractApiMaterial(target, "0.0.18")
    val mtimesBefore = os.list(dir).map(p => p.last -> os.mtime(p)).toMap

    val dirAgain = FlowAuthoring.extractApiMaterial(target, "0.0.18")

    assertEquals(dirAgain, dir)
    val mtimesAfter = os.list(dir).map(p => p.last -> os.mtime(p)).toMap
    assertEquals(mtimesAfter, mtimesBefore)

  test("extractApiMaterial self-heals a half-populated leftover dir"):
    val target = TempDirs.dir()
    val dir = target / "orca-api-0.0.18"
    os.makeDir.all(dir)
    os.write(dir / "README.md", "stale-partial-content")

    val result = FlowAuthoring.extractApiMaterial(target, "0.0.18")

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
    FlowAuthoring.initialPrompt(
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
    val text = FlowAuthoring.initialPrompt(
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
      FlowAuthoring.initialPrompt(
        "sync issues nightly",
        targetPath,
        apiDir,
        "dev"
      )
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

  // --- localFilenameSlug ---

  test(
    "localFilenameSlug slugs the first four meaningful words, dropping stopwords"
  ):
    assertEquals(
      FlowAuthoring.localFilenameSlug(
        "Implement a rate limiter for the login endpoint"
      ),
      "implement-rate-limiter-login.sc"
    )

  test("localFilenameSlug lowercases and strips punctuation"):
    assertEquals(
      FlowAuthoring.localFilenameSlug("Fix the bug: NPE on save!!!"),
      "fix-bug-npe-save.sc"
    )

  test("localFilenameSlug handles unicode letters as ordinary word characters"):
    assertEquals(
      FlowAuthoring.localFilenameSlug("Résumé café münchen naïve"),
      "résumé-café-münchen-naïve.sc"
    )

  test(
    "localFilenameSlug falls back to new-flow.sc when nothing survives filtering (all stopwords)"
  ):
    assertEquals(
      FlowAuthoring.localFilenameSlug("the a to for and"),
      "new-flow.sc"
    )

  test("localFilenameSlug falls back to new-flow.sc on punctuation-only input"):
    assertEquals(FlowAuthoring.localFilenameSlug("!!! ??? ..."), "new-flow.sc")

  test("localFilenameSlug takes only the first four words, ignoring the rest"):
    assertEquals(
      FlowAuthoring.localFilenameSlug("one two three four five six seven"),
      "one-two-three-four.sc"
    )

  // --- slugArgv ---

  test("slugArgv: claude uses -p/--print"):
    assertEquals(
      FlowAuthoring.slugArgv(BackendTag.ClaudeCode, "suggest a name"),
      Seq("claude", "-p", "suggest a name")
    )

  test("slugArgv: codex uses the exec subcommand"):
    assertEquals(
      FlowAuthoring.slugArgv(BackendTag.Codex, "suggest a name"),
      Seq("codex", "exec", "suggest a name")
    )

  test("slugArgv: pi uses -p/--print"):
    assertEquals(
      FlowAuthoring.slugArgv(BackendTag.Pi, "suggest a name"),
      Seq("pi", "-p", "suggest a name")
    )

  test("slugArgv: gemini uses -p/--prompt (the non-interactive/headless flag)"):
    assertEquals(
      FlowAuthoring.slugArgv(BackendTag.Gemini, "suggest a name"),
      Seq("gemini", "-p", "suggest a name")
    )

  test("slugArgv: opencode uses the run subcommand"):
    assertEquals(
      FlowAuthoring.slugArgv(BackendTag.Opencode, "suggest a name"),
      Seq("opencode", "run", "suggest a name")
    )

  // --- slugPrompt ---

  test("slugPrompt states the goal and asks for only the filename"):
    val text = FlowAuthoring.slugPrompt("sync issues nightly")
    assert(text.contains("sync issues nightly"))
    assert(text.contains("Reply with ONLY the filename"))

  // --- sanitizeSlug ---

  test("sanitizeSlug kebab-cases junk (mixed case, punctuation, spaces)"):
    assertEquals(
      FlowAuthoring.sanitizeSlug("Sure! Here's one: My Cool Flow"),
      "sure-here-s-one-my-cool-flow.sc"
    )

  test("sanitizeSlug strips an existing .sc suffix before re-appending it"):
    assertEquals(
      FlowAuthoring.sanitizeSlug("my-cool-flow.sc"),
      "my-cool-flow.sc"
    )

  test("sanitizeSlug falls back to new-flow.sc on punctuation/whitespace only"):
    assertEquals(FlowAuthoring.sanitizeSlug("   !!! ... ???  "), "new-flow.sc")

  test("sanitizeSlug bounds an unreasonably long reply"):
    val long = ("word" * 30)
    val result = FlowAuthoring.sanitizeSlug(long)
    assert(result.stripSuffix(".sc").length <= 50, result)

  // --- suggestFilename ---

  test("suggestFilename sanitizes the harness's last non-blank output line"):
    val runner: (Seq[String], Long) => Option[String] =
      (_, _) => Some("Thinking...\n\nSure, how about: My Cool Flow\n")
    val result =
      FlowAuthoring.suggestFilename(
        BackendTag.ClaudeCode,
        "sync issues nightly",
        runner = runner
      )
    assertEquals(result, "sure-how-about-my-cool-flow.sc")

  test(
    "suggestFilename falls back to localFilenameSlug when the harness is unreachable/times out"
  ):
    val runner: (Seq[String], Long) => Option[String] = (_, _) => None
    val goal = "Implement a rate limiter for the login endpoint"
    assertEquals(
      FlowAuthoring
        .suggestFilename(BackendTag.ClaudeCode, goal, runner = runner),
      FlowAuthoring.localFilenameSlug(goal)
    )

  test(
    "suggestFilename falls back to localFilenameSlug when the harness reply sanitizes to nothing"
  ):
    val runner: (Seq[String], Long) => Option[String] =
      (_, _) => Some("   !!! ... ???  ")
    val goal = "Implement a rate limiter for the login endpoint"
    assertEquals(
      FlowAuthoring
        .suggestFilename(BackendTag.ClaudeCode, goal, runner = runner),
      FlowAuthoring.localFilenameSlug(goal)
    )

  test("suggestFilename passes slugArgv/slugPrompt through to the runner"):
    var seenArgv: Seq[String] = Nil
    val runner: (Seq[String], Long) => Option[String] =
      (argv, _) => { seenArgv = argv; Some("my-flow") }
    val _ = FlowAuthoring.suggestFilename(
      BackendTag.Gemini,
      "sync issues nightly",
      runner = runner
    )
    assertEquals(
      seenArgv,
      FlowAuthoring.slugArgv(
        BackendTag.Gemini,
        FlowAuthoring.slugPrompt("sync issues nightly")
      )
    )

  // --- forkSlugPrompt ---

  test(
    "forkSlugPrompt states the source name, description, and changes, and asks for the filename only"
  ):
    val text = FlowAuthoring.forkSlugPrompt(
      "implement-interactive.sc",
      Some("Implements a task interactively, pausing for user review"),
      "review the plan"
    )
    assert(text.contains("implement-interactive.sc"))
    assert(
      text.contains("Implements a task interactively, pausing for user review")
    )
    assert(text.contains("review the plan"))
    assert(text.contains("Reply with ONLY the filename"))

  test(
    "forkSlugPrompt states a placeholder when the source has no description"
  ):
    val text = FlowAuthoring.forkSlugPrompt("implement.sc", None, "add logging")
    assert(text.contains("(no description)"))

  // --- suggestFilenameForFork ---

  test(
    "suggestFilenameForFork sanitizes the harness's last non-blank output line"
  ):
    val runner: (Seq[String], Long) => Option[String] =
      (_, _) => Some("Sure, how about: implement-plan-review\n")
    val result = FlowAuthoring.suggestFilenameForFork(
      "implement-interactive.sc",
      Some("Implements a task interactively"),
      "review the plan",
      runner = runner
    )
    assertEquals(result, "sure-how-about-implement-plan-review.sc")

  test(
    "suggestFilenameForFork falls back to forkFilenameDefault when the harness is unreachable/times out"
  ):
    val runner: (Seq[String], Long) => Option[String] = (_, _) => None
    val result = FlowAuthoring.suggestFilenameForFork(
      "implement-interactive.sc",
      Some("Implements a task interactively"),
      "review the plan",
      runner = runner
    )
    assertEquals(
      result,
      FlowAuthoring.forkFilenameDefault("implement-interactive.sc")
    )

  test(
    "suggestFilenameForFork falls back to forkFilenameDefault when the harness reply sanitizes to nothing"
  ):
    val runner: (Seq[String], Long) => Option[String] =
      (_, _) => Some("   !!! ... ???  ")
    val result = FlowAuthoring.suggestFilenameForFork(
      "implement-interactive.sc",
      None,
      "review the plan",
      runner = runner
    )
    assertEquals(
      result,
      FlowAuthoring.forkFilenameDefault("implement-interactive.sc")
    )

  test(
    "suggestFilenameForFork passes forkSlugPrompt's text through to the runner"
  ):
    var seenPrompt: String = ""
    val runner: (Seq[String], Long) => Option[String] =
      (argv, _) => { seenPrompt = argv.last; Some("my-fork") }
    val _ = FlowAuthoring.suggestFilenameForFork(
      "implement-interactive.sc",
      Some("desc"),
      "review the plan",
      runner = runner
    )
    assertEquals(
      seenPrompt,
      FlowAuthoring.forkSlugPrompt(
        "implement-interactive.sc",
        Some("desc"),
        "review the plan"
      )
    )

  // --- forkFilenameDefault ---

  test("forkFilenameDefault strips .sc and appends -fork.sc"):
    assertEquals(
      FlowAuthoring.forkFilenameDefault("implement.sc"),
      "implement-fork.sc"
    )

  test("forkFilenameDefault appends -fork.sc even without a .sc source name"):
    assertEquals(
      FlowAuthoring.forkFilenameDefault("implement"),
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
      FlowAuthoring.resolveForkSource(source, "implement.sc", cwd, apiDir),
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
      FlowAuthoring.resolveForkSource(source, "implement.sc", cwd, apiDir)
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
      FlowAuthoring.resolveForkSource(source, "implement.sc", cwd, apiDir)

    assertEquals(resolved, apiDir / "implement.sc")
    assertEquals(os.read(resolved), "// pre-existing copy\n")

  // --- forkPrompt ---

  private def fork: String =
    FlowAuthoring.forkPrompt(
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

  test(
    "forkPrompt instructs creating the target by copying the source and applying changes"
  ):
    assert(fork.contains("Create the Orca flow"))
    assert(fork.contains("by copying"))

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
    val text = FlowAuthoring.forkPrompt(
      multilineChanges,
      targetPath / os.up / "implement.sc",
      targetPath,
      apiDir,
      "0.0.18"
    )
    assert(text.contains("  add a rate limit"))
    assert(text.contains("  and log rejected requests"))

  // --- editPrompt (edit-by-agent's task — same tail as forkPrompt, distinct
  // opening: worded as an edit, since that's the action the user picked) ---

  private def edit: String =
    FlowAuthoring.editPrompt(
      "add a rate limit",
      targetPath / os.up / "implement.sc",
      targetPath,
      apiDir,
      "0.0.18"
    )

  test("editPrompt states the source path, the changes, and the target path"):
    assert(edit.contains("add a rate limit"))
    assert(edit.contains(targetPath.toString))
    assert(edit.contains((targetPath / os.up / "implement.sc").toString))

  test(
    "editPrompt is worded as an edit, not a fork — no 'Create'/'by copying'"
  ):
    assert(edit.contains("Edit the Orca flow"))
    assert(!edit.contains("Create the Orca flow"))
    assert(!edit.contains("by copying"))

  test("editPrompt points at the extracted README and both examples"):
    assert(edit.contains((apiDir / "README.md").toString))
    assert(edit.contains((apiDir / "implement.sc").toString))
    assert(edit.contains((apiDir / "implement-interactive.sc").toString))

  test("editPrompt states the compile-check line"):
    assert(edit.contains(s"scala-cli compile $targetPath"))

  test("editPrompt states the runtime-vs-compile-time rules caveat"):
    assert(edit.contains("enforced at runtime"))

  test("editPrompt indents a multi-line change description as its own block"):
    val multilineChanges = "add a rate limit\nand log rejected requests"
    val text = FlowAuthoring.editPrompt(
      multilineChanges,
      targetPath / os.up / "implement.sc",
      targetPath,
      apiDir,
      "0.0.18"
    )
    assert(text.contains("  add a rate limit"))
    assert(text.contains("  and log rejected requests"))

  // --- resolveTarget / prepareTarget ---

  test("prepareAutoTarget: a free name is used as-is"):
    val dir = TempDirs.dir()
    val target = FlowAuthoring.prepareAutoTarget(
      CreateTier.Project,
      "my-flow",
      dir,
      dir / "global"
    )
    assertEquals(target.flowPath, dir / ".orca" / "flows" / "my-flow.sc")

  test("prepareAutoTarget: a taken name is uniquified with -2, -3, …"):
    val dir = TempDirs.dir()
    os.write(dir / ".orca" / "flows" / "my-flow.sc", "", createFolders = true)
    os.write(dir / ".orca" / "flows" / "my-flow-2.sc", "")
    val target = FlowAuthoring.prepareAutoTarget(
      CreateTier.Project,
      "my-flow.sc",
      dir,
      dir / "global"
    )
    assertEquals(target.flowPath, dir / ".orca" / "flows" / "my-flow-3.sc")

  test("normalizedFileName adds a .sc suffix when missing"):
    assertEquals(FlowAuthoring.normalizedFileName("my-flow"), "my-flow.sc")

  test("normalizedFileName leaves an existing .sc suffix alone"):
    assertEquals(FlowAuthoring.normalizedFileName("my-flow.sc"), "my-flow.sc")

  test("resolveTarget (Project): saves under .orca/flows, cwd is workDir"):
    val workDir = os.root / "repo"
    val globalFlows = os.root / "home" / "u" / ".config" / "orca" / "flows"
    assertEquals(
      FlowAuthoring.resolveTarget(
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
      FlowAuthoring.resolveTarget(
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
      FlowAuthoring.prepareTarget(
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
    val result = FlowAuthoring.prepareTarget(
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
      FlowAuthoring.prepareTarget(
        CreateTier.Project,
        "my-flow",
        workDir,
        TempDirs.dir()
      )
    result match
      case Left(message) => assert(message.contains("already exists"))
      case Right(path) =>
        fail(s"expected a collision refusal, got Right($path)")

  // --- tierCwd ---

  test("tierCwd (Project) is workDir"):
    val workDir = os.root / "repo"
    assertEquals(
      FlowAuthoring.tierCwd(CreateTier.Project, workDir, os.root / "flows"),
      workDir
    )

  test("tierCwd (Global) is the global flows dir's parent"):
    val globalFlows = os.root / "home" / "u" / ".config" / "orca" / "flows"
    assertEquals(
      FlowAuthoring.tierCwd(CreateTier.Global, os.root / "repo", globalFlows),
      os.root / "home" / "u" / ".config" / "orca"
    )

  // --- skeletonFlow ---

  test("skeletonFlow states the same version pins as initialPrompt"):
    val skeleton = FlowAuthoring.skeletonFlow("0.0.18")
    assert(skeleton.contains("//> using scala 3.8.4"))
    assert(skeleton.contains("""//> using dep "org.virtuslab::orca:0.0.18""""))
    assert(skeleton.contains("//> using jvm 21"))

  test("skeletonFlow (dev version) injects ivy2Local, matching initialPrompt"):
    assert(FlowAuthoring.skeletonFlow("dev").contains("ivy2Local"))

  test("skeletonFlow (release version) has no ivy2Local repository line"):
    assert(!FlowAuthoring.skeletonFlow("0.0.18").contains("ivy2Local"))

  test("skeletonFlow's line 1 is the description placeholder"):
    val lines = FlowAuthoring.skeletonFlow("0.0.18").linesIterator.toList
    assertEquals(lines.head, "// TODO: describe what this flow does")

  test(
    "skeletonFlow imports the API and has a flow(...) body with a TODO"
  ):
    val skeleton = FlowAuthoring.skeletonFlow("0.0.18")
    assert(skeleton.contains("import orca.{*, given}"))
    assert(skeleton.contains("flow(OrcaArgs(args)):"))
    assert(skeleton.contains("// TODO: implement the flow"))

  test(
    "skeletonFlow's flow(...) body has a real statement, not just a comment — a comment-only indented block is a Scala 3 syntax error"
  ):
    val skeleton = FlowAuthoring.skeletonFlow("0.0.18")
    val bodyLines = skeleton.linesIterator
      .dropWhile(!_.contains("flow(OrcaArgs(args)):"))
      .drop(1)
      .takeWhile(_.trim.nonEmpty)
      .toList
    assert(
      bodyLines.exists(line => !line.trim.startsWith("//")),
      s"expected a non-comment statement in the flow body, got: $bodyLines"
    )
