package orca.shell.cli

import mainargs.ParserForMethods
import orca.agents.BackendTag
import orca.runner.{ManifestSession, RunManifest}
import orca.settings.{AgentSettings, AgentSpec}
import orca.shell.actions.{AuthorOutcome, SessionSelection}
import orca.shell.create.CreateTier
import orca.shell.flows.{CustomizeTier, DiscoveredFlow, FlowOrigin}
import orca.shell.run.LaunchResult
import orca.shell.sessions.{ReadRun, SessionPicker}
import orca.testkit.TempDirs

class CliTest extends munit.FunSuite:

  // --- mainargs shape: happy path vs missing-required (usage error) ---
  //
  // `invoke` runs the real Cli object exactly as `dispatch` would, but keeps
  // the raw Either so a test can tell "mainargs rejected the shape, the
  // method never ran" (Left) apart from "the method ran and decided
  // something itself" (Right) — every subcommand's own logic (a bogus flow
  // name, the tty gate) only ever executes on the Right side, so these tests
  // never touch a real flow/harness/console.

  private def invoke(args: String*): Either[String, Any] =
    ParserForMethods(Cli).runEither(args, autoPrintHelpAndExit = None)

  private def parses(args: String*): Boolean = invoke(args*).isRight

  test(
    "run: flow + task positional and both flags parse (fails later, at flow resolution)"
  ):
    assertEquals(
      invoke("run", "no-such-flow.sc", "a task", "--verbose", "--honor-pin"),
      Right(1)
    )

  test("run: the required flow positional missing is a usage error"):
    assert(!parses("run"))

  test("view: a flow ref parses (fails later, at flow resolution)"):
    assertEquals(invoke("view", "no-such-flow.sc"), Right(1))

  test("view: the required flow positional missing is a usage error"):
    assert(!parses("view"))

  test(
    "view: --plain and --color together is a usage error at the method level"
  ):
    assertEquals(
      invoke("view", "no-such-flow.sc", "--plain", "--color"),
      Right(2)
    )

  // --- view: --plain/--color resolution (injected tty, no real console) ---

  test("resolveHighlight: neither flag auto-detects from tty"):
    assertEquals(
      Cli.resolveHighlight(plain = false, color = false, tty = true),
      Right(true)
    )
    assertEquals(
      Cli.resolveHighlight(plain = false, color = false, tty = false),
      Right(false)
    )

  test("resolveHighlight: --plain forces off regardless of tty"):
    assertEquals(
      Cli.resolveHighlight(plain = true, color = false, tty = true),
      Right(false)
    )

  test("resolveHighlight: --color forces on regardless of tty"):
    assertEquals(
      Cli.resolveHighlight(plain = false, color = true, tty = false),
      Right(true)
    )

  test("resolveHighlight: --plain and --color together is rejected"):
    assertEquals(
      Cli.resolveHighlight(plain = true, color = true, tty = false),
      Left("--plain and --color are mutually exclusive")
    )

  test("runView: highlight=true emits ANSI escapes, highlight=false doesn't"):
    val dir = TempDirs.dir()
    os.write(dir / "x.sc", "// desc\nval a = 1\n")
    val plainOut =
      captured(
        assertEquals(Cli.runView(dir, "x.sc", highlight = false), ExitCodes.Ok)
      )
    val colorOut =
      captured(
        assertEquals(Cli.runView(dir, "x.sc", highlight = true), ExitCodes.Ok)
      )
    assert(!plainOut.contains("\u001b"), plainOut)
    assert(colorOut.contains("\u001b"), colorOut)

  test(
    "edit: well-formed args are accepted by mainargs (the tty gate is the method's own logic)"
  ):
    // sbt's forked test JVM has no controlling console, so this always hits
    // the tty gate — Right(2), never Left — proving the ARGS themselves
    // parsed fine, and proving the gate itself never NPEs/hangs off-tty.
    assertEquals(invoke("edit", "no-such-flow.sc", "--to", "project"), Right(2))

  test("edit: the required flow positional missing is a usage error"):
    assert(!parses("edit"))

  test("parseCustomizeTier: 'project' resolves to CustomizeTier.Project"):
    assertEquals(
      Cli.parseCustomizeTier("project"),
      Right(CustomizeTier.Project)
    )

  test("parseCustomizeTier: 'global' resolves to CustomizeTier.Global"):
    assertEquals(Cli.parseCustomizeTier("global"), Right(CustomizeTier.Global))

  test("parseCustomizeTier: anything else is a usage error naming the value"):
    assertEquals(
      Cli.parseCustomizeTier("bogus"),
      Left("--to must be 'project' or 'global', got 'bogus'")
    )

  test("create: missing the required --goal is a usage error"):
    assert(!parses("create", "--harness", "claude"))

  test(
    "create: --goal present parses; off-tty the method itself refuses with exit 2"
  ):
    assertEquals(invoke("create", "--goal", "do a thing"), Right(2))

  test("fork: missing the required --changes is a usage error"):
    assert(!parses("fork", "source.sc"))

  test(
    "fork: --changes present parses; off-tty the method itself refuses with exit 2"
  ):
    assertEquals(
      invoke("fork", "source.sc", "--changes", "make it better"),
      Right(2)
    )

  // requireNonBlank backs create/fork's --goal/--changes guard; the tty gate
  // (already covered above) runs before it, so exercising the guard itself
  // — rather than the full argv path, which always hits the tty gate first
  // off-tty — is what actually proves it rejects blank input.

  test("requireNonBlank: a non-blank value passes"):
    assertEquals(Cli.requireNonBlank("goal", "do a thing"), Right(()))

  test("requireNonBlank: an empty value is a usage error naming the flag"):
    assertEquals(Cli.requireNonBlank("goal", ""), Left("--goal can't be empty"))

  test("requireNonBlank: a whitespace-only value is a usage error"):
    assertEquals(
      Cli.requireNonBlank("changes", "   "),
      Left("--changes can't be empty")
    )

  test(
    "continue: no selector parses (--list not given, so it also hits the tty gate off-tty)"
  ):
    assertEquals(invoke("continue"), Right(2))

  test("continue: --list --json parses and never needs a tty"):
    assert(parses("continue", "--list", "--json"))

  test(
    "config: an unknown --coding harness is rejected before any settings file is touched"
  ):
    // Never exercises a valid role flag here — that would write the real
    // user-global settings file; `RunConfigTest`-style coverage below uses an
    // explicit temp path instead.
    assertEquals(invoke("config", "--coding", "not-a-real-harness"), Right(2))

  test(
    "config: no flags parses (a read-only `show` of the real global settings file)"
  ):
    assert(parses("config"))

  test("rediscover-stack: --yes parses"):
    assert(parses("rediscover-stack", "--yes"))

  test("list: no flags parses"):
    assert(parses("list"))

  test("list: --json parses"):
    assert(parses("list", "--json"))

  test("commandNames lists exactly the nine documented subcommands"):
    assertEquals(
      Cli.commandNames,
      Set(
        "run",
        "view",
        "edit",
        "create",
        "fork",
        "continue",
        "config",
        "rediscover-stack",
        "list"
      )
    )

  test("dispatch: an unknown subcommand is a usage error, not a crash"):
    assertEquals(Cli.dispatch(Seq("no-such-command")), ExitCodes.UsageError)

  test("dispatch: requires a non-empty argv (Main.main's own contract)"):
    intercept[IllegalArgumentException](Cli.dispatch(Seq.empty))

  test(
    "dispatch: '<command> --help' prints that command's own help and exits 0"
  ):
    val out =
      captured(assertEquals(Cli.dispatch(Seq("run", "--help")), ExitCodes.Ok))
    assert(out.contains("run"), out)

  test("commandHelp renders a known subcommand's signature"):
    assert(Cli.commandHelp("view").exists(_.contains("view")))

  test("commandHelp is None for an unknown subcommand"):
    assertEquals(Cli.commandHelp("no-such-command"), None)

  // --- tty gate (injected, no real console needed) ---

  test("requireTty: Right(()) when tty is true"):
    assertEquals(Cli.requireTty("create", tty = true), Right(()))

  test("requireTty: Left with a one-line hint when tty is false"):
    assertEquals(
      Cli.requireTty("create", tty = false),
      Left("`orca create` needs a terminal; run it interactively")
    )

  // --- readTask (injected tty + stdin reader; never touches real stdin/console) ---

  test("readTask: a non-blank positional task wins outright"):
    assertEquals(
      Cli.readTask(Some("do it"), tty = false, () => "unused"),
      Right("do it")
    )

  test("readTask: a blank positional task is rejected"):
    assertEquals(
      Cli.readTask(Some("   "), tty = true, () => "unused"),
      Left("task text can't be empty")
    )

  test("readTask: omitted + tty is a usage error, and never calls readStdin"):
    var called = false
    val result = Cli.readTask(None, tty = true, () => { called = true; "x" })
    assert(result.isLeft)
    assert(!called, "must not read stdin when it's a terminal")

  test("readTask: omitted + piped stdin reads and trims it"):
    assertEquals(
      Cli.readTask(None, tty = false, () => "  piped task\n"),
      Right("piped task")
    )

  test("readTask: omitted + empty piped stdin is a usage error"):
    assert(Cli.readTask(None, tty = false, () => "   \n").isLeft)

  // --- AuthorOutcome -> exit code mapping ---

  test("exitFor: Launched propagates the harness's own exit code"):
    assertEquals(Cli.exitFor(AuthorOutcome.Launched(0)), 0)
    assertEquals(Cli.exitFor(AuthorOutcome.Launched(7)), 7)

  test("exitFor: NotLaunched maps to ActionFailed"):
    assertEquals(Cli.exitFor(AuthorOutcome.NotLaunched), ExitCodes.ActionFailed)

  // --- LaunchResult -> exit code mapping ---

  test("exitCodeFor maps Ok/Failed/Cancelled"):
    assertEquals(Cli.exitCodeFor(LaunchResult.Ok), 0)
    assertEquals(Cli.exitCodeFor(LaunchResult.Failed(3)), 3)
    assertEquals(
      Cli.exitCodeFor(LaunchResult.Cancelled),
      ExitCodes.SignalKilled
    )

  // --- create/fork flag resolution: harness + yolo default-on ---

  test("resolveHarness: an unknown name is rejected, listing the valid ones"):
    assertEquals(
      Cli.resolveHarness(Some("chatgpt")),
      Left(
        "unknown harness 'chatgpt' — valid: claude, codex, gemini, opencode, pi"
      )
    )

  test("resolveHarness: a known name resolves to its BackendTag"):
    assertEquals(Cli.resolveHarness(Some("codex")), Right(BackendTag.Codex))

  test("authorParams: yolo defaults on when neither flag is given"):
    val result =
      Cli.authorParams(
        mainargs.Flag(),
        mainargs.Flag(),
        mainargs.Flag(),
        Some("codex")
      )
    assertEquals(result, Right((CreateTier.Project, BackendTag.Codex, true)))

  test("authorParams: --no-yolo turns yolo off"):
    val result = Cli.authorParams(
      mainargs.Flag(),
      mainargs.Flag(),
      mainargs.Flag(true),
      Some("codex")
    )
    assertEquals(result.map(_._3), Right(false))

  test("authorParams: --global selects CreateTier.Global"):
    val result = Cli.authorParams(
      mainargs.Flag(true),
      mainargs.Flag(),
      mainargs.Flag(),
      Some("codex")
    )
    assertEquals(result.map(_._1), Right(CreateTier.Global))

  test("authorParams: --yolo and --no-yolo together is rejected"):
    assertEquals(
      Cli.authorParams(
        mainargs.Flag(),
        mainargs.Flag(true),
        mainargs.Flag(true),
        Some("codex")
      ),
      Left("--yolo and --no-yolo are mutually exclusive")
    )

  // --- create/fork filename guard: no path separators (security review) ---

  test(
    "validateFileName: a name containing '..' plus '/' is rejected outright"
  ):
    assertEquals(
      Cli.validateFileName("../escape.sc"),
      Left(
        "'../escape.sc' isn't a valid flow filename — path separators aren't allowed"
      )
    )

  test("validateFileName: a nested-directory name is rejected too"):
    assert(Cli.validateFileName("sub/dir.sc").isLeft)

  test("validateFileName: a bare filename is accepted"):
    assertEquals(Cli.validateFileName("my-flow.sc"), Right(()))

  test(
    "safePrepareTarget: delegates to CreateFlow.prepareTarget for an ordinary name"
  ):
    val dir = TempDirs.dir()
    val result =
      Cli.safePrepareTarget(CreateTier.Project, "x.sc", dir, dir / "global")
    assertEquals(
      result.map(_.flowPath),
      Right(dir / ".orca" / "flows" / "x.sc")
    )

  // --- config: partial merge over an explicit settings path ---

  private def withTempPath(body: os.Path => Unit): Unit =
    val dir = TempDirs.dir()
    body(dir / "settings.properties")

  test(
    "runConfig: no flags shows AgentSettings.empty as '(not set)' on an absent file"
  ):
    withTempPath: path =>
      val out = captured(
        assertEquals(
          Cli.runConfig(path, None, None, None, force = false),
          ExitCodes.Ok
        )
      )
      assert(out.contains("planning: (not set)"), out)
      assert(out.contains("coding: (not set)"), out)
      assert(out.contains("review: (not set)"), out)

  test(
    "runConfig: a subset of flags merges over the existing file, preserving the rest"
  ):
    withTempPath: path =>
      os.write(path, "planningAgent = claude\ncodingAgent = codex\n")
      assertEquals(
        Cli.runConfig(path, None, Some("gemini"), None, force = false),
        ExitCodes.Ok
      )
      val written = orca.settings.SettingsFile
        .parse(os.read(path), orca.settings.SettingsScope.UserGlobal)
        .toOption
        .get
        .agents
      assertEquals(
        written.planning,
        Some(AgentSpec(BackendTag.ClaudeCode, None))
      )
      assertEquals(written.coding, Some(AgentSpec(BackendTag.Gemini, None)))

  test(
    "runConfig: an invalid role value is a usage error, with nothing written"
  ):
    withTempPath: path =>
      assertEquals(
        Cli.runConfig(path, None, Some("not-a-harness"), None, force = false),
        ExitCodes.UsageError
      )
      assert(!os.exists(path))

  test(
    "runConfig: a malformed file without --force refuses, leaving it untouched"
  ):
    withTempPath: path =>
      os.write(path, "not a valid line\n")
      assertEquals(
        Cli.runConfig(path, Some("codex"), None, None, force = false),
        ExitCodes.ActionFailed
      )
      assertEquals(os.read(path), "not a valid line\n")

  test(
    "runConfig: a malformed file with --force rewrites it, keeping only the given subset"
  ):
    withTempPath: path =>
      os.write(path, "not a valid line\n")
      assertEquals(
        Cli.runConfig(path, Some("codex"), None, None, force = true),
        ExitCodes.Ok
      )
      val written = orca.settings.SettingsFile
        .parse(os.read(path), orca.settings.SettingsScope.UserGlobal)
        .toOption
        .get
        .agents
      assertEquals(
        written,
        AgentSettings(planning = Some(AgentSpec(BackendTag.Codex, None)))
      )

  test("renderAgents: a set model pin renders as harness:model"):
    val text = Cli.renderAgents(
      AgentSettings(coding =
        Some(AgentSpec(BackendTag.ClaudeCode, Some("sonnet")))
      )
    )
    assert(text.contains("coding: claude:sonnet"), text)

  // --- rediscover-stack: --yes required off a terminal ---

  test(
    "runRediscoverStack: no stack lines is a no-op, exit 0, no tty/--yes needed"
  ):
    val dir = TempDirs.dir()
    assertEquals(
      Cli.runRediscoverStack(dir, yes = false, tty = false),
      ExitCodes.Ok
    )

  test(
    "runRediscoverStack: stack lines present, off-tty, no --yes -> usage error, untouched"
  ):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content =
      "# orca settings — edit freely, commit with the project.\n" +
        "# Delete the stack lines (format/lint/test, commented ones too) to re-run auto-discovery.\n" +
        "format = cargo fmt\n"
    os.write.over(path, content)
    assertEquals(
      Cli.runRediscoverStack(dir, yes = false, tty = false),
      ExitCodes.UsageError
    )
    assertEquals(os.read(path), content)

  test(
    "runRediscoverStack: stack lines present, --yes clears without needing a tty"
  ):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content =
      "# orca settings — edit freely, commit with the project.\n" +
        "# Delete the stack lines (format/lint/test, commented ones too) to re-run auto-discovery.\n" +
        "format = cargo fmt\n"
    os.write.over(path, content)
    assertEquals(
      Cli.runRediscoverStack(dir, yes = true, tty = false),
      ExitCodes.Ok
    )
    assert(!orca.settings.SettingsFile.hasStackLines(os.read(path)))

  // --- list --json shape ---

  test("toFlowRow carries name/description/origin/path/shadows"):
    val flow = DiscoveredFlow(
      name = "x.sc",
      description = Some("does a thing"),
      origin = FlowOrigin.Project,
      path = os.root / "tmp" / "x.sc",
      shadows = List(FlowOrigin.Global, FlowOrigin.BuiltIn)
    )
    val row = Cli.toFlowRow(flow)
    assertEquals(row.name, "x.sc")
    assertEquals(row.description, Some("does a thing"))
    assertEquals(row.origin, "project")
    assertEquals(row.shadows, List("global", "built-in"))

  test("runList --json emits one JSON object per discovered flow"):
    val dir = TempDirs.dir()
    os.write(
      dir / ".orca" / "flows" / "x.sc",
      "// does a thing\nval x = 1",
      createFolders = true
    )
    val out =
      captured(assertEquals(Cli.runList(dir, json = true), ExitCodes.Ok))
    assert(out.contains(""""name":"x.sc""""), out)
    assert(out.contains(""""origin":"project""""), out)
    assert(out.contains(""""description":"does a thing""""), out)

  test("runList prints a plain table when --json is not given"):
    val dir = TempDirs.dir()
    os.write(
      dir / ".orca" / "flows" / "x.sc",
      "// does a thing\nval x = 1",
      createFolders = true
    )
    val out =
      captured(assertEquals(Cli.runList(dir, json = false), ExitCodes.Ok))
    assert(out.contains("x.sc"), out)
    assert(out.contains("does a thing"), out)
    assert(!out.contains("{"), out)

  // --- continue: selector resolution (index / name / newest) ---

  private def manifest(
      workDir: String = "/work",
      startedAt: String,
      sessions: List[ManifestSession]
  ): RunManifest =
    RunManifest(
      orcaVersion = "0.0.test",
      flow = Some("a-flow.sc"),
      workDir = workDir,
      pid = 1,
      startedAt = startedAt,
      finishedAt = None,
      outcome = "succeeded",
      sessions = sessions
    )

  private def durable(
      sessionName: String,
      lastActiveAt: String,
      wireId: Option[String] = Some("uuid"),
      reason: Option[String] = None
  ): ManifestSession =
    ManifestSession(
      harness = "ClaudeCode",
      wireId = wireId,
      resumable = wireId.isDefined,
      reason = reason,
      agent = "main",
      role = None,
      stage = None,
      sessionName = Some(sessionName),
      kind = "durable",
      firstSeenAt = lastActiveAt,
      lastActiveAt = lastActiveAt
    )

  private def runsFixture(): List[ReadRun] =
    List(
      ReadRun(
        manifest(
          startedAt = "2026-07-18T09:00:00Z",
          sessions = List(durable("newest", "2026-07-18T09:45:00Z"))
        ),
        crashed = false
      ),
      ReadRun(
        manifest(
          startedAt = "2026-07-17T09:00:00Z",
          sessions = List(
            durable("older", "2026-07-17T09:30:00Z"),
            durable(
              "unresumable",
              "2026-07-17T09:29:00Z",
              wireId = None,
              reason = Some("pi has no id")
            )
          )
        ),
        crashed = false
      )
    )

  test("resolveSelection: no selector resumes the newest durable lineage"):
    val result = SessionPicker.resolveSelection(runsFixture(), None)
    assertEquals(result.map(_.session.sessionName), Right(Some("newest")))

  test("resolveSelection: no selector on an empty run list is an error"):
    assertEquals(
      SessionPicker.resolveSelection(Nil, None),
      Left("no sessions recorded yet")
    )

  test(
    "resolveSelection: a numeric selector picks that 1-based row from the full listing"
  ):
    // Full listing (expanded) order: newest, older, unresumable.
    assertEquals(
      SessionPicker
        .resolveSelection(runsFixture(), Some("2"))
        .map(_.session.sessionName),
      Right(Some("older"))
    )

  test(
    "resolveSelection: an out-of-range index is an error naming the valid range"
  ):
    assertEquals(
      SessionPicker.resolveSelection(runsFixture(), Some("99")),
      Left("no session at index 99 — see `orca continue --list` (1-3)")
    )

  test(
    "resolveSelection: an index pointing at an unresumable row reports the stored reason"
  ):
    assertEquals(
      SessionPicker.resolveSelection(runsFixture(), Some("3")),
      Left("session 3 isn't resumable — pi has no id")
    )

  test("resolveSelection: a name selector resolves that durable lineage"):
    assertEquals(
      SessionPicker
        .resolveSelection(runsFixture(), Some("older"))
        .map(_.session.sessionName),
      Right(Some("older"))
    )

  test("resolveSelection: an unknown name is an error"):
    assertEquals(
      SessionPicker.resolveSelection(runsFixture(), Some("no-such-session")),
      Left(
        "no session named 'no-such-session' found — see `orca continue --list`"
      )
    )

  test(
    "resolveSelection: a name selector on an unresumable session reports the reason"
  ):
    assertEquals(
      SessionPicker.resolveSelection(runsFixture(), Some("unresumable")),
      Left("session 'unresumable' isn't resumable — pi has no id")
    )

  private def durableAgent(
      agent: String,
      sessionName: String,
      lastActiveAt: String
  ): ManifestSession =
    ManifestSession(
      harness = "ClaudeCode",
      wireId = Some("uuid"),
      resumable = true,
      reason = None,
      agent = agent,
      role = None,
      stage = None,
      sessionName = Some(sessionName),
      kind = "durable",
      firstSeenAt = lastActiveAt,
      lastActiveAt = lastActiveAt
    )

  test(
    "selectByName: a name shared by two distinct lineages (different agents) is ambiguous"
  ):
    val runs = List(
      ReadRun(
        manifest(
          startedAt = "2026-07-18T09:00:00Z",
          sessions = List(
            durableAgent("agentA", "shared", "2026-07-18T09:30:00Z"),
            durableAgent("agentB", "shared", "2026-07-18T09:20:00Z")
          )
        ),
        crashed = false
      )
    )
    assertEquals(
      SessionPicker.selectByName(runs, "shared"),
      Left("'shared' is ambiguous — matches agents: agentA, agentB")
    )

  test(
    "sessionListingRows numbers rows 1-based in the same order continue <n> uses"
  ):
    val rows = Cli.sessionListingRows(runsFixture())
    assertEquals(
      rows.map(r => (r.index, r.sessionName)),
      List((1, "newest"), (2, "older"), (3, "unresumable"))
    )
    assertEquals(rows.map(_.resumable), List(true, true, false))

  // --- resumeNotice: the resolved session's identity, printed before resuming ---

  test("resumeNotice: names the session, harness, and workDir"):
    val run = runsFixture().head
    val selection =
      SessionSelection(
        run.manifest,
        run.manifest.sessions.head,
        crashed = false
      )
    assertEquals(
      Cli.resumeNotice(selection),
      "resuming session 'newest' [claude], in /work"
    )

  test("resumeNotice: includes the stage when the session has one"):
    val run = runsFixture().head
    val withStage =
      run.manifest.sessions.head.copy(stage = Some("Task: fix a bug"))
    val selection = SessionSelection(run.manifest, withStage, crashed = false)
    assertEquals(
      Cli.resumeNotice(selection),
      "resuming session 'newest' [claude], stage 'Task: fix a bug', in /work"
    )

  test("resumeNotice: mentions a crashed run"):
    val run = runsFixture().head
    val selection =
      SessionSelection(run.manifest, run.manifest.sessions.head, crashed = true)
    assertEquals(
      Cli.resumeNotice(selection),
      "resuming session 'newest' [claude], in /work (crashed)"
    )

  // --- stdout/stderr separation (CLI review finding 1) ---

  private def writeFutureManifest(dir: os.Path): Unit =
    val json =
      """{
        |  "manifestVersion": 2,
        |  "orcaVersion": "0.0.test",
        |  "workDir": "/work",
        |  "pid": 1,
        |  "startedAt": "2026-07-18T09:00:00Z",
        |  "outcome": "succeeded",
        |  "sessions": []
        |}""".stripMargin
    os.write(
      dir / ".orca" / "cache" / "runs" / "future.json",
      json,
      createFolders = true
    )

  test(
    "runContinue --list --json: a manifestVersion warning lands on stderr, only JSON on stdout"
  ):
    val dir = TempDirs.dir()
    writeFutureManifest(dir)
    val (out, err) = capturedBoth(
      assertEquals(
        Cli.runContinue(dir, None, list = true, json = true, tty = false),
        ExitCodes.Ok
      )
    )
    assertEquals(out.trim, "[]")
    assert(err.contains("manifestVersion 2 is newer"), err)

  private def writeCrashedManifest(dir: os.Path): Unit =
    val json =
      """{
        |  "manifestVersion": 1,
        |  "orcaVersion": "0.0.test",
        |  "workDir": "/work",
        |  "pid": 999999,
        |  "startedAt": "2026-07-18T09:00:00Z",
        |  "outcome": "running",
        |  "sessions": [{
        |    "harness": "ClaudeCode",
        |    "wireId": "uuid",
        |    "resumable": true,
        |    "agent": "main",
        |    "sessionName": "main",
        |    "kind": "durable",
        |    "firstSeenAt": "2026-07-18T09:00:00Z",
        |    "lastActiveAt": "2026-07-18T09:00:00Z"
        |  }]
        |}""".stripMargin
    os.write(
      dir / ".orca" / "cache" / "runs" / "a.json",
      json,
      createFolders = true
    )

  test("runContinue --list --json: a crashed run reports crashed=true"):
    val dir = TempDirs.dir()
    writeCrashedManifest(dir)
    val out = captured(
      assertEquals(
        Cli.runContinue(dir, None, list = true, json = true, tty = false),
        ExitCodes.Ok
      )
    )
    assert(out.contains("\"crashed\":true"), out)

  test("runContinue --list: a crashed run's table row is suffixed (crashed)"):
    val dir = TempDirs.dir()
    writeCrashedManifest(dir)
    val out = captured(
      assertEquals(
        Cli.runContinue(dir, None, list = true, json = false, tty = false),
        ExitCodes.Ok
      )
    )
    assert(out.contains("main (crashed)"), out)

  test(
    "runContinue: no selector prints the resume notice to stderr before attempting to resume"
  ):
    val dir = TempDirs.dir()
    val goneWorkDir = (dir / "gone").toString
    val json =
      s"""{
        |  "manifestVersion": 1,
        |  "orcaVersion": "0.0.test",
        |  "workDir": "$goneWorkDir",
        |  "pid": 1,
        |  "startedAt": "2026-07-18T09:00:00Z",
        |  "outcome": "succeeded",
        |  "sessions": [{
        |    "harness": "ClaudeCode",
        |    "wireId": "uuid",
        |    "resumable": true,
        |    "agent": "main",
        |    "stage": "Task: fix a bug",
        |    "sessionName": "main",
        |    "kind": "durable",
        |    "firstSeenAt": "2026-07-18T09:00:00Z",
        |    "lastActiveAt": "2026-07-18T09:00:00Z"
        |  }]
        |}""".stripMargin
    os.write(
      dir / ".orca" / "cache" / "runs" / "a.json",
      json,
      createFolders = true
    )
    val (out, err) = capturedBoth(
      assertEquals(
        Cli.runContinue(dir, None, list = false, json = false, tty = true),
        ExitCodes.ActionFailed
      )
    )
    assertEquals(out, "")
    assert(err.contains("resuming session 'main' [claude]"), err)
    assert(err.contains("stage 'Task: fix a bug'"), err)
    assert(err.contains("no longer exists"), err)

  private def captured(body: => Unit): String =
    val buffer = new java.io.ByteArrayOutputStream()
    Console.withOut(new java.io.PrintStream(buffer))(body)
    buffer.toString

  private def capturedBoth(body: => Unit): (String, String) =
    val outBuffer = new java.io.ByteArrayOutputStream()
    val errBuffer = new java.io.ByteArrayOutputStream()
    Console.withOut(new java.io.PrintStream(outBuffer)):
      Console.withErr(new java.io.PrintStream(errBuffer))(body)
    (outBuffer.toString, errBuffer.toString)
