package orca.shell.cli

import mainargs.ParserForMethods
import orca.agents.BackendTag
import orca.runner.{ManifestSession, RunManifest}
import orca.settings.{AgentSettings, AgentSpec}
import orca.shell.actions.AuthorOutcome
import orca.shell.create.CreateTier
import orca.shell.flows.{DiscoveredFlow, FlowOrigin}
import orca.shell.run.LaunchResult
import orca.shell.sessions.ReadRun
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
    "edit: well-formed args are accepted by mainargs (the tty gate is the method's own logic)"
  ):
    // sbt's forked test JVM has no controlling console, so this always hits
    // the tty gate — Right(2), never Left — proving the ARGS themselves
    // parsed fine, and proving the gate itself never NPEs/hangs off-tty.
    assertEquals(invoke("edit", "no-such-flow.sc", "--to", "project"), Right(2))

  test("edit: the required flow positional missing is a usage error"):
    assert(!parses("edit"))

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
    val result = Cli.resolveSelection(runsFixture(), None)
    assertEquals(result.map(_.session.sessionName), Right(Some("newest")))

  test("resolveSelection: no selector on an empty run list is an error"):
    assertEquals(
      Cli.resolveSelection(Nil, None),
      Left("no sessions recorded yet")
    )

  test(
    "resolveSelection: a numeric selector picks that 1-based row from the full listing"
  ):
    // Full listing (expanded) order: newest, older, unresumable.
    assertEquals(
      Cli.resolveSelection(runsFixture(), Some("2")).map(_.session.sessionName),
      Right(Some("older"))
    )

  test(
    "resolveSelection: an out-of-range index is an error naming the valid range"
  ):
    assertEquals(
      Cli.resolveSelection(runsFixture(), Some("99")),
      Left("no session at index 99 — see `orca continue --list` (1-3)")
    )

  test(
    "resolveSelection: an index pointing at an unresumable row reports the stored reason"
  ):
    assertEquals(
      Cli.resolveSelection(runsFixture(), Some("3")),
      Left("session 3 isn't resumable — pi has no id")
    )

  test("resolveSelection: a name selector resolves that durable lineage"):
    assertEquals(
      Cli
        .resolveSelection(runsFixture(), Some("older"))
        .map(_.session.sessionName),
      Right(Some("older"))
    )

  test("resolveSelection: an unknown name is an error"):
    assertEquals(
      Cli.resolveSelection(runsFixture(), Some("no-such-session")),
      Left(
        "no session named 'no-such-session' found — see `orca continue --list`"
      )
    )

  test(
    "resolveSelection: a name selector on an unresumable session reports the reason"
  ):
    assertEquals(
      Cli.resolveSelection(runsFixture(), Some("unresumable")),
      Left("session 'unresumable' isn't resumable — pi has no id")
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

  private def captured(body: => Unit): String =
    val buffer = new java.io.ByteArrayOutputStream()
    Console.withOut(new java.io.PrintStream(buffer))(body)
    buffer.toString
