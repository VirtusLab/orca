package orca.shell.cli

import mainargs.ParserForMethods
import orca.StagePath
import orca.agents.{BackendTag, SessionKey}
import orca.runner.manifest.{AttemptStatus, ManifestSession}
import orca.settings.{AgentSettings, AgentSpec, SettingsFile, SettingsScope}
import orca.shell.ScanDirs
import orca.shell.actions.SessionAction
import orca.shell.create.CreateTier
import orca.discovery.Origin
import orca.shell.flows.DiscoveredFlow
import orca.shell.run.LaunchResult
import orca.shell.sessions.{
  ManifestFixtures,
  RecordedAttempt,
  SessionIndex,
  SessionPicker,
  SessionRef
}
import orca.shell.sessions.ManifestFixtures.{
  durable,
  ephemeral,
  manifest,
  writeManifest
}
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

  test(
    "run: --skip-branch parses too (fails later, at flow resolution)"
  ):
    assertEquals(
      invoke(
        "run",
        "no-such-flow.sc",
        "a task",
        "--verbose",
        "--skip-branch",
        "--honor-pin"
      ),
      Right(1)
    )

  test(
    "run: --keep-changes parses too (fails later, at flow resolution)"
  ):
    assertEquals(
      invoke("run", "no-such-flow.sc", "a task", "--keep-changes"),
      Right(1)
    )

  test(
    "run: --worktree parses too (fails later, at flow resolution)"
  ):
    assertEquals(
      invoke("run", "no-such-flow.sc", "a task", "--worktree"),
      Right(1)
    )

  test(
    "run: --worktree with --skip-branch is refused before the flow is resolved"
  ):
    // A usage error rather than the flow-not-found 1 the cases above get: the
    // refusal runs first, so no scala-cli is ever spawned.
    val (_, err) = capturedBoth(
      assertEquals(
        invoke(
          "run",
          "no-such-flow.sc",
          "a task",
          "--worktree",
          "--skip-branch"
        ),
        Right(ExitCodes.UsageError)
      )
    )
    assert(err.contains("--worktree") && err.contains("--skip-branch"), err)

  test("run: --worktree with --keep-changes is refused the same way"):
    val (_, err) = capturedBoth(
      assertEquals(
        invoke(
          "run",
          "no-such-flow.sc",
          "a task",
          "--worktree",
          "--keep-changes"
        ),
        Right(ExitCodes.UsageError)
      )
    )
    assert(err.contains("--worktree") && err.contains("--keep-changes"), err)
    // Names the pair the user actually typed, not the other one.
    assert(!err.contains("--skip-branch"), err)

  test(
    "run: --branch with --skip-branch is refused before the flow is resolved"
  ):
    val (_, err) = capturedBoth(
      assertEquals(
        invoke(
          "run",
          "no-such-flow.sc",
          "a task",
          "--branch",
          "feature-x",
          "--skip-branch"
        ),
        Right(ExitCodes.UsageError)
      )
    )
    assert(err.contains("--branch") && err.contains("--skip-branch"), err)

  test("run: an invalid --branch value is refused before the flow is resolved"):
    val (_, err) = capturedBoth(
      assertEquals(
        invoke("run", "no-such-flow.sc", "a task", "--branch", "a..b"),
        Right(ExitCodes.UsageError)
      )
    )
    assert(err.contains("a..b"), err)

  test(
    "run: --prompt with a value starting with '-' parses (fails later, at flow resolution)"
  ):
    assertEquals(
      invoke("run", "no-such-flow.sc", "--prompt", "- add X\n- fix Y"),
      Right(1)
    )

  test("run: a task given both positionally and with --prompt is refused"):
    val (_, err) = capturedBoth(
      assertEquals(
        invoke("run", "no-such-flow.sc", "a task", "--prompt", "another"),
        Right(ExitCodes.UsageError)
      )
    )
    assert(err.contains("--prompt"), err)

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
      ViewCli.resolveHighlight(plain = false, color = false, tty = true),
      Right(true)
    )
    assertEquals(
      ViewCli.resolveHighlight(plain = false, color = false, tty = false),
      Right(false)
    )

  test("resolveHighlight: --plain forces off regardless of tty"):
    assertEquals(
      ViewCli.resolveHighlight(plain = true, color = false, tty = true),
      Right(false)
    )

  test("resolveHighlight: --color forces on regardless of tty"):
    assertEquals(
      ViewCli.resolveHighlight(plain = false, color = true, tty = false),
      Right(true)
    )

  test("resolveHighlight: --plain and --color together is rejected"):
    assertEquals(
      ViewCli.resolveHighlight(plain = true, color = true, tty = false),
      Left("--plain and --color are mutually exclusive")
    )

  test("runView: highlight=true emits ANSI escapes, highlight=false doesn't"):
    val dir = TempDirs.dir()
    os.write(dir / "x.sc", "// desc\nval a = 1\n")
    val plainOut =
      captured(
        assertEquals(
          ViewCli.runView(dir, "x.sc", highlight = false),
          ExitCodes.Ok
        )
      )
    val colorOut =
      captured(
        assertEquals(
          ViewCli.runView(dir, "x.sc", highlight = true),
          ExitCodes.Ok
        )
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

  test("parseCustomizeTier: 'project' resolves to CreateTier.Project"):
    assertEquals(
      EditCli.parseCustomizeTier("project"),
      Right(CreateTier.Project)
    )

  test("parseCustomizeTier: 'global' resolves to CreateTier.Global"):
    assertEquals(EditCli.parseCustomizeTier("global"), Right(CreateTier.Global))

  test("parseCustomizeTier: anything else is a usage error naming the value"):
    assertEquals(
      EditCli.parseCustomizeTier("bogus"),
      Left("--to must be 'project' or 'global', got 'bogus'")
    )

  test(
    "parseCustomizeTier: a non-default flag name is used in the error instead of --to"
  ):
    assertEquals(
      EditCli.parseCustomizeTier("bogus", "--edit"),
      Left("--edit must be 'project' or 'global', got 'bogus'")
    )

  test("create: missing the required goal positional is a usage error"):
    assert(!parses("create"))

  test(
    "create: the goal positional parses; off-tty the method itself refuses with exit 2"
  ):
    assertEquals(invoke("create", "do a thing"), Right(2))

  test("create: --name parses alongside the goal positional"):
    assertEquals(
      invoke("create", "do a thing", "--name", "custom.sc"),
      Right(2)
    )

  test("create: the old --goal flag is no longer recognized"):
    assert(!parses("create", "--goal", "do a thing"))

  test("fork: missing the required changes positional is a usage error"):
    assert(!parses("fork", "source.sc"))

  test(
    "fork: source and changes positionals parse; off-tty the method itself refuses with exit 2"
  ):
    assertEquals(
      invoke("fork", "source.sc", "make it better"),
      Right(2)
    )

  test("fork: --name parses alongside the source/changes positionals"):
    assertEquals(
      invoke("fork", "source.sc", "make it better", "--name", "custom.sc"),
      Right(2)
    )

  test("fork: the old --changes flag is no longer recognized"):
    assert(!parses("fork", "source.sc", "--changes", "make it better"))

  // requireNonBlank backs create/fork's goal/changes positional guard; the
  // tty gate (already covered above) runs before it, so exercising the guard
  // itself — rather than the full argv path, which always hits the tty gate
  // first off-tty — is what actually proves it rejects blank input.

  test("requireNonBlank: a non-blank value passes"):
    assertEquals(Cli.requireNonBlank("goal", "do a thing"), Right(()))

  test("requireNonBlank: an empty value is a usage error naming the argument"):
    assertEquals(Cli.requireNonBlank("goal", ""), Left("goal can't be empty"))

  test("requireNonBlank: a whitespace-only value is a usage error"):
    assertEquals(
      Cli.requireNonBlank("changes", "   "),
      Left("changes can't be empty")
    )

  test(
    "continue: no selector parses (--list not given, so it also hits the tty gate off-tty)"
  ):
    assertEquals(invoke("continue"), Right(2))

  test("continue: --list --json parses and never needs a tty"):
    assert(parses("continue", "--list", "--json"))

  test(
    "config: an unknown --coding-agent harness is rejected before any settings file is touched"
  ):
    // Never exercises a valid role flag here — that would write the real
    // user-global settings file; `RunConfigTest`-style coverage below uses an
    // explicit temp path instead.
    assertEquals(
      invoke("config", "--coding-agent", "not-a-real-harness"),
      Right(2)
    )

  test(
    "config: no flags parses (a read-only `show` of the real global settings file)"
  ):
    assert(parses("config"))

  test(
    "config --edit: well-formed args are accepted by mainargs; off-tty the method itself refuses with exit 2"
  ):
    // Same off-tty convention as `edit`'s own test above — sbt's forked test
    // JVM always hits the tty gate here, proving the ARGS parsed fine.
    assertEquals(invoke("config", "--edit", "project"), Right(2))

  test(
    "config --edit combined with a role flag is a usage error, not a silently-dropped flag"
  ):
    // The conflict check runs before the tty gate, so this is Right(2)
    // regardless of the test JVM's own tty — proving it's the dedicated
    // conflict error, not the tty gate, that fired.
    val (_, err) = capturedBoth(
      assertEquals(
        invoke("config", "--edit", "project", "--coding-agent", "codex"),
        Right(2)
      )
    )
    assert(err.contains("--edit can't be combined with role flags"), err)

  test("config --edit combined with --force is a usage error too"):
    assertEquals(
      invoke("config", "--edit", "global", "--force"),
      Right(2)
    )

  test("clear-stack: --yes parses"):
    assert(parses("clear-stack", "--yes"))

  test("clear-stack: the old rediscover-stack name is no longer recognized"):
    assert(!parses("rediscover-stack", "--yes"))

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
        "clear-stack",
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
      RunCli.readTask(Some("do it"), tty = false, () => "unused"),
      Right("do it")
    )

  test("readTask: a blank positional task is rejected"):
    assertEquals(
      RunCli.readTask(Some("   "), tty = true, () => "unused"),
      Left("task text can't be empty")
    )

  test("readTask: omitted + tty is a usage error, and never calls readStdin"):
    var called = false
    val result = RunCli.readTask(None, tty = true, () => { called = true; "x" })
    assert(result.isLeft)
    assert(!called, "must not read stdin when it's a terminal")

  test("readTask: omitted + piped stdin reads and trims it"):
    assertEquals(
      RunCli.readTask(None, tty = false, () => "  piped task\n"),
      Right("piped task")
    )

  test("readTask: omitted + empty piped stdin is a usage error"):
    assert(RunCli.readTask(None, tty = false, () => "   \n").isLeft)

  test(
    "readTask: omitted + empty piped stdin names the next action, like the tty message does"
  ):
    assertEquals(
      RunCli.readTask(None, tty = false, () => "   \n"),
      Left(
        "no task given, and stdin was empty — pass the task as an " +
          "argument (--prompt=<text> if it starts with '-'), or pipe " +
          "non-empty input"
      )
    )

  // --- LaunchResult -> exit code mapping (shared by RunCli and AuthorCli) ---

  test("Cli.exitCodeFor maps Ok/Failed/Cancelled"):
    assertEquals(Cli.exitCodeFor(LaunchResult.Ok), 0)
    assertEquals(Cli.exitCodeFor(LaunchResult.Failed(3)), 3)
    assertEquals(
      Cli.exitCodeFor(LaunchResult.Cancelled),
      ExitCodes.SignalKilled
    )

  // --- create/fork: --harness/--yolo/--no-yolo are gone (ADR 0021 §9 amendment) ---

  test("create: --harness is no longer a recognized flag"):
    assert(!parses("create", "do a thing", "--harness", "claude"))

  test("create: --yolo is no longer a recognized flag"):
    assert(!parses("create", "do a thing", "--yolo"))

  test("fork: --no-yolo is no longer a recognized flag"):
    assert(
      !parses("fork", "source.sc", "make it better", "--no-yolo")
    )

  test("create: --global still parses (fails later, off-tty)"):
    assertEquals(
      invoke("create", "do a thing", "--global"),
      Right(2)
    )

  // --- create/fork filename guard: no path separators (security review) ---

  test(
    "validateFileName: a name containing '..' plus '/' is rejected outright"
  ):
    assertEquals(
      AuthorCli.validateFileName("../escape.sc"),
      Left(
        "'../escape.sc' isn't a valid flow filename — path separators aren't allowed"
      )
    )

  test("validateFileName: a nested-directory name is rejected too"):
    assert(AuthorCli.validateFileName("sub/dir.sc").isLeft)

  test("validateFileName: a bare filename is accepted"):
    assertEquals(AuthorCli.validateFileName("my-flow.sc"), Right(()))

  test(
    "safePrepareTarget: delegates to FlowAuthoring.prepareTarget for an ordinary name"
  ):
    val dir = TempDirs.dir()
    val result =
      AuthorCli.safePrepareTarget(
        CreateTier.Project,
        "x.sc",
        dir,
        dir / "global"
      )
    assertEquals(
      result.map(_.flowPath),
      Right(dir / ".orca" / "flows" / "x.sc")
    )

  // --- resolveTarget: an explicit --name never forces the (possibly
  // agent-backed) auto-name suggestion, since it's by-name and only the
  // `None` branch ever touches it ---

  test("resolveTarget: an explicit name never evaluates autoName"):
    val dir = TempDirs.dir()
    val result = AuthorCli.resolveTarget(
      CreateTier.Project,
      Some("explicit.sc"),
      throw new RuntimeException("autoName must not be evaluated"),
      dir,
      dir / "global"
    )
    assertEquals(
      result.map(_.flowPath),
      Right(dir / ".orca" / "flows" / "explicit.sc")
    )

  test("resolveTarget: no name falls through to autoName"):
    val dir = TempDirs.dir()
    val result =
      AuthorCli.resolveTarget(
        CreateTier.Project,
        None,
        "suggested.sc",
        dir,
        dir / "global"
      )
    assertEquals(
      result.map(_.flowPath),
      Right(dir / ".orca" / "flows" / "suggested.sc")
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
          ConfigCli.runConfig(path, None, None, None, force = false),
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
        ConfigCli.runConfig(path, None, Some("gemini"), None, force = false),
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
        ConfigCli
          .runConfig(path, None, Some("not-a-harness"), None, force = false),
        ExitCodes.UsageError
      )
      assert(!os.exists(path))

  test(
    "runConfig: a malformed file without --force refuses, leaving it untouched"
  ):
    withTempPath: path =>
      os.write(path, "not a valid line\n")
      assertEquals(
        ConfigCli.runConfig(path, Some("codex"), None, None, force = false),
        ExitCodes.ActionFailed
      )
      assertEquals(os.read(path), "not a valid line\n")

  test(
    "runConfig: a malformed file with --force rewrites it, keeping only the given subset"
  ):
    withTempPath: path =>
      os.write(path, "not a valid line\n")
      assertEquals(
        ConfigCli.runConfig(path, Some("codex"), None, None, force = true),
        ExitCodes.Ok
      )
      // The gate: --force rewrites the malformed file into one that parses
      // cleanly (junk gone). The exact merged content is covered by the
      // subset-merge test above.
      val written = os.read(path)
      assert(
        orca.settings.SettingsFile
          .parse(written, orca.settings.SettingsScope.UserGlobal)
          .isRight,
        written
      )
      assert(!written.contains("not a valid line"), written)

  // --- runEdit: tty-gate and tier parsing (never reaches the real editor
  // spawn — both failure modes short-circuit the for-comprehension before
  // `withTerminal` is ever called).

  test("runEdit: off-tty is a usage error, naming the command"):
    withTempPath: path =>
      assertEquals(
        ConfigCli.runEdit("project", tty = false, TempDirs.dir(), path),
        ExitCodes.UsageError
      )
      assert(!os.exists(path))

  test(
    "runEdit: an invalid tier value is a usage error naming --edit, not --to"
  ):
    withTempPath: path =>
      val (_, err) = capturedBoth(
        assertEquals(
          ConfigCli.runEdit("bogus", tty = true, TempDirs.dir(), path),
          ExitCodes.UsageError
        )
      )
      assert(!os.exists(path))
      assert(
        err.contains("--edit must be 'project' or 'global', got 'bogus'"),
        err
      )
      assert(!err.contains("--to"), err)

  // --- run: --edit's mutual exclusion with role flags/--force ---

  test("run: --edit with a role flag is a usage error naming the conflict"):
    withTempPath: path =>
      assertEquals(
        ConfigCli.run(
          path,
          planning = None,
          coding = Some("codex"),
          review = None,
          force = false,
          edit = Some("project"),
          tty = true,
          workDir = TempDirs.dir()
        ),
        ExitCodes.UsageError
      )

  test("run: --edit with --force is a usage error too"):
    withTempPath: path =>
      assertEquals(
        ConfigCli.run(
          path,
          planning = None,
          coding = None,
          review = None,
          force = true,
          edit = Some("project"),
          tty = true,
          workDir = TempDirs.dir()
        ),
        ExitCodes.UsageError
      )

  test(
    "run: --edit alone (no role flags) is not rejected by the conflict check"
  ):
    withTempPath: path =>
      // tty = false so this doesn't actually spawn an editor — both this and
      // the conflict case are UsageError, so the message is what proves it's
      // the tty gate that fired here, not the conflict check.
      val (_, err) = capturedBoth(
        assertEquals(
          ConfigCli.run(
            path,
            planning = None,
            coding = None,
            review = None,
            force = false,
            edit = Some("project"),
            tty = false,
            workDir = TempDirs.dir()
          ),
          ExitCodes.UsageError
        )
      )
      assert(err.contains("needs a terminal"), err)
      assert(!err.contains("can't be combined"), err)

  test("run: no --edit delegates to runConfig unchanged"):
    withTempPath: path =>
      val out = captured(
        assertEquals(
          ConfigCli.run(
            path,
            planning = None,
            coding = None,
            review = None,
            force = false,
            edit = None,
            tty = true,
            workDir = TempDirs.dir()
          ),
          ExitCodes.Ok
        )
      )
      assert(out.contains("planning: (not set)"), out)

  test("renderAgents: a set model pin renders as harness:model"):
    val text = ConfigCli.renderAgents(
      AgentSettings(coding =
        Some(AgentSpec(BackendTag.ClaudeCode, Some("sonnet")))
      )
    )
    assert(text.contains("coding: claude:sonnet"), text)

  // --- clear-stack: --yes required off a terminal ---

  test(
    "runClearStack: no stack lines is a no-op, exit 0, no tty/--yes needed"
  ):
    val dir = TempDirs.dir()
    assertEquals(
      StackCli.runClearStack(dir, yes = false, tty = false),
      ExitCodes.Ok
    )

  test(
    "runClearStack: stack lines present, off-tty, no --yes -> usage error, untouched"
  ):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content = SettingsFile.Header + "\nformat = cargo fmt\n"
    os.write.over(path, content)
    assertEquals(
      StackCli.runClearStack(dir, yes = false, tty = false),
      ExitCodes.UsageError
    )
    assertEquals(os.read(path), content)

  test(
    "runClearStack: stack lines present, --yes clears without needing a tty"
  ):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content = SettingsFile.Header + "\nformat = cargo fmt\n"
    os.write.over(path, content)
    assertEquals(
      StackCli.runClearStack(dir, yes = true, tty = false),
      ExitCodes.Ok
    )
    assertEquals(
      SettingsFile.parse(os.read(path), SettingsScope.Project).map(_.stack),
      Right(None)
    )

  // --- list --json shape ---

  test("toFlowRow carries name/description/origin/path/shadows"):
    val flow = DiscoveredFlow(
      name = "x.sc",
      description = Some("does a thing"),
      origin = Origin.Project,
      path = os.root / "tmp" / "x.sc",
      shadows = List(Origin.Global, Origin.BuiltIn)
    )
    val row = Tables.toFlowRow(flow)
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
      captured(assertEquals(ListCli.runList(dir, json = true), ExitCodes.Ok))
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
      captured(assertEquals(ListCli.runList(dir, json = false), ExitCodes.Ok))
    assert(out.contains("x.sc"), out)
    assert(out.contains("does a thing"), out)
    assert(!out.contains("{"), out)

  // --- continue: selector resolution (id / name / branch / newest) ---

  private def attemptsFixture(): List[RecordedAttempt] =
    List(
      ManifestFixtures.recorded(
        manifest(
          startedAt = "2026-07-18T09:00:00Z",
          branch = Some("feature/newest"),
          sessions = List(
            durable(
              sessionName = "newest",
              lastActiveAt = "2026-07-18T09:45:00Z"
            )
          )
        ),
        crashed = false
      ),
      ManifestFixtures.recorded(
        manifest(
          startedAt = "2026-07-17T09:00:00Z",
          branch = Some("feature/older"),
          sessions = List(
            durable(
              sessionName = "older",
              lastActiveAt = "2026-07-17T09:30:00Z"
            ),
            durable(
              sessionName = "unresumable",
              lastActiveAt = "2026-07-17T09:29:00Z",
              wireId = None
            )
          )
        ),
        crashed = false
      )
    )

  test("resolve: no selector resumes the newest durable lineage"):
    val result = SessionIndex.of(attemptsFixture()).resolve(None)
    assertEquals(
      result.map(_.session.minted.map(_.name)),
      Right(Some("newest"))
    )

  test("resolve: no selector on an empty attempt list is an error"):
    assertEquals(
      SessionIndex.of(Nil).resolve(None),
      Left("no sessions recorded yet")
    )

  test(
    "resolve: no selector with only ephemeral sessions is an error"
  ):
    val attempts = List(
      ManifestFixtures.recorded(
        manifest(
          startedAt = "2026-07-18T09:00:00Z",
          sessions = List(
            durable(sessionName = "plan", lastActiveAt = "2026-07-18T09:45:00Z")
              .copy(minted = None)
          )
        ),
        crashed = false
      )
    )
    assertEquals(
      SessionIndex.of(attempts).resolve(None),
      Left("no durable session to continue yet — see `orca continue --list`")
    )

  /** The id `--list` shows for the `position`th session of fixture attempt
    * `attempt`.
    */
  private def fixtureRef(attempt: Int, position: Int): String =
    SessionRef(attemptsFixture()(attempt).id, position).spelling

  test("resolve: an id selector picks that session"):
    assertEquals(
      SessionIndex
        .of(attemptsFixture())
        .resolve(Some(fixtureRef(1, 1)))
        .map(_.session.minted.map(_.name)),
      Right(Some("older"))
    )

  test(
    "resolve: an id keeps its session after a newer one is recorded"
  ):
    val newer = ManifestFixtures.recorded(
      manifest(
        startedAt = "2026-07-19T09:00:00Z",
        sessions = List(
          durable(sessionName = "fresh", lastActiveAt = "2026-07-19T09:30:00Z")
        )
      )
    )
    assertEquals(
      SessionIndex
        .of(newer :: attemptsFixture())
        .resolve(Some(fixtureRef(1, 1)))
        .map(_.session.minted.map(_.name)),
      Right(Some("older"))
    )

  test("resolve: an id naming no recorded session is an error"):
    val ref = fixtureRef(1, 9)
    assertEquals(
      SessionIndex.of(attemptsFixture()).resolve(Some(ref)),
      Left(
        s"no session $ref — it may have been pruned; see `orca continue --list`"
      )
    )

  test("resolve: an id pointing at an unresumable session says why"):
    val ref = fixtureRef(1, 2)
    assertEquals(
      SessionIndex.of(attemptsFixture()).resolve(Some(ref)),
      Left(
        s"session $ref isn't resumable — ClaudeCode session has no resumable id"
      )
    )

  test("resolve: an id reaches an ephemeral session"):
    val attempt = ManifestFixtures.recorded(
      manifest(sessions = List(ephemeral(agent = "reviewer")))
    )
    assertEquals(
      SessionIndex
        .of(List(attempt))
        .resolve(Some(SessionRef(attempt.id, 1).spelling))
        .map(_.session.agent),
      Right("reviewer")
    )

  test("resolve: a selector spelled like an id is never matched as a branch"):
    val ref = fixtureRef(1, 9)
    val attempt = ManifestFixtures.recorded(
      manifest(
        startedAt = "2026-07-16T09:00:00Z",
        branch = Some(ref),
        sessions = List(durable(lastActiveAt = "2026-07-16T09:30:00Z"))
      )
    )
    assertEquals(
      SessionIndex.of(attempt :: attemptsFixture()).resolve(Some(ref)),
      Left(
        s"no session $ref — it may have been pruned; see `orca continue --list`"
      )
    )

  test("resolve: a name selector resolves that durable lineage"):
    assertEquals(
      SessionIndex
        .of(attemptsFixture())
        .resolve(Some("older"))
        .map(_.session.minted.map(_.name)),
      Right(Some("older"))
    )

  test("resolve: an unknown name is an error"):
    assertEquals(
      SessionIndex.of(attemptsFixture()).resolve(Some("no-such-session")),
      Left(
        "no session or branch named 'no-such-session' found — see `orca continue --list`"
      )
    )

  test(
    "resolve: a name selector on an unresumable session says why"
  ):
    assertEquals(
      SessionIndex.of(attemptsFixture()).resolve(Some("unresumable")),
      Left(
        "session 'unresumable' isn't resumable — ClaudeCode session has no resumable id"
      )
    )

  test(
    "resolve: a branch selector picks the most recently active lineage"
  ):
    def onBranch(
        startedAt: String,
        sessionName: String,
        lastActiveAt: String
    ): RecordedAttempt =
      ManifestFixtures.recorded(
        manifest(
          startedAt = startedAt,
          branch = Some("feature/x"),
          sessions = List(
            durable(sessionName = sessionName, lastActiveAt = lastActiveAt)
          )
        ),
        crashed = false
      )
    // The older attempt holds the more recently active session.
    val attempts = List(
      onBranch("2026-07-18T10:00:00Z", "planner", "2026-07-18T10:10:00Z"),
      onBranch("2026-07-18T09:00:00Z", "implementer", "2026-07-18T11:00:00Z")
    )
    assertEquals(
      SessionIndex
        .of(attempts)
        .resolve(Some("feature/x"))
        .map(_.session.minted.map(_.name)),
      Right(Some("implementer"))
    )

  test(
    "resolve: a branch whose newest session is unresumable says why"
  ):
    val attempts = List(
      ManifestFixtures.recorded(
        manifest(
          branch = Some("feature/broken"),
          sessions = List(
            durable(sessionName = "ok", lastActiveAt = "2026-07-18T09:00:00Z"),
            durable(
              sessionName = "broken",
              lastActiveAt = "2026-07-18T09:30:00Z",
              wireId = None
            )
          )
        ),
        crashed = false
      )
    )
    assertEquals(
      SessionIndex.of(attempts).resolve(Some("feature/broken")),
      Left(
        "the newest session on branch 'feature/broken' isn't resumable — " +
          "ClaudeCode session has no resumable id"
      )
    )

  test("resolve: a selector naming a session and a branch is refused"):
    val attempts = attemptsFixture() :+ ManifestFixtures.recorded(
      manifest(
        startedAt = "2026-07-16T09:00:00Z",
        branch = Some("older"),
        sessions = List(
          durable(sessionName = "other", lastActiveAt = "2026-07-16T09:30:00Z")
        )
      ),
      crashed = false
    )
    assertEquals(
      SessionIndex.of(attempts).resolve(Some("older")),
      Left(
        "'older' names both a session and a branch; run " +
          "`orca continue --list` and pick one by its id"
      )
    )

  test(
    "resolve: a branch recorded in two working directories is refused"
  ):
    def onBranch(workDir: String, lastActiveAt: String): RecordedAttempt =
      ManifestFixtures.recorded(
        manifest(
          workDir = workDir,
          startedAt = "2026-07-18T08:00:00Z",
          branch = Some("feature/x"),
          sessions = List(durable(lastActiveAt = lastActiveAt))
        ),
        crashed = false
      )
    val attempts = List(
      onBranch("/repo/a", "2026-07-18T09:30:00Z"),
      onBranch("/repo/b", "2026-07-18T08:30:00Z")
    )
    assertEquals(
      SessionIndex.of(attempts).resolve(Some("feature/x")),
      Left(
        "'feature/x' is ambiguous — matches working directories: " +
          "/repo/a, /repo/b; run `orca continue --list` and pick one by its id"
      )
    )

  private def durableAgent(
      agent: String,
      sessionName: String,
      lastActiveAt: String
  ): ManifestSession =
    durable(
      agent = agent,
      sessionName = sessionName,
      lastActiveAt = lastActiveAt
    )

  test(
    "resolve: sessions under one name differ only by stage, so the newest wins"
  ):
    // The minting stage tells rows apart for a reader; it does not address
    // them, so `continue implementer` must not start demanding one.
    val attempts = List(
      ManifestFixtures.recorded(
        manifest(
          startedAt = "2026-07-18T09:00:00Z",
          sessions = List(
            durable(
              sessionName = "implementer",
              lastActiveAt = "2026-07-18T09:30:00Z",
              sessionStage = "Task: parse the input#0"
            ),
            durable(
              sessionName = "implementer",
              lastActiveAt = "2026-07-18T10:30:00Z",
              sessionStage = "Task: wire the parser#0"
            )
          )
        ),
        crashed = false
      )
    )
    assertEquals(
      SessionIndex
        .of(attempts)
        .resolve(Some("implementer"))
        .map(_.session.minted),
      Right(
        Some(
          SessionKey(
            name = "implementer",
            stage = StagePath.FlowBody.child("Task: wire the parser", 0)
          )
        )
      )
    )

  test(
    "resolve: a name shared by two distinct lineages (different agents) is ambiguous"
  ):
    val attempts = List(
      ManifestFixtures.recorded(
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
      SessionIndex.of(attempts).resolve(Some("shared")),
      Left(
        "'shared' is ambiguous — matches agents: agentA, agentB; " +
          "run `orca continue --list` and pick one by its id"
      )
    )

  test(
    "resolve: the same name in two worktrees is ambiguous, naming the trees"
  ):
    // Flow session names are static, so two parallel --worktree runs on
    // unrelated tasks both record "shared" under the same agent. They are
    // different conversations (harness sessions are cwd-scoped) and must not
    // resolve silently to the newer one.
    val attempts = List(
      ManifestFixtures.recorded(
        manifest(
          workDir = "/repo/.orca/worktrees/aaaaaaaaaaaa",
          startedAt = "2026-07-18T09:00:00Z",
          sessions = List(
            durable(
              sessionName = "shared",
              lastActiveAt = "2026-07-18T09:30:00Z"
            )
          )
        ),
        crashed = false
      ),
      ManifestFixtures.recorded(
        manifest(
          workDir = "/repo/.orca/worktrees/bbbbbbbbbbbb",
          startedAt = "2026-07-18T08:00:00Z",
          sessions = List(
            durable(
              sessionName = "shared",
              lastActiveAt = "2026-07-18T08:30:00Z"
            )
          )
        ),
        crashed = false
      )
    )
    assertEquals(
      SessionIndex.of(attempts).resolve(Some("shared")),
      Left(
        "'shared' is ambiguous — matches working directories: " +
          "/repo/.orca/worktrees/aaaaaaaaaaaa, /repo/.orca/worktrees/bbbbbbbbbbbb; " +
          "run `orca continue --list` and pick one by its id"
      )
    )
    // Both are primary rows, and each says which tree it is in.
    val labels = SessionPicker
      .sessionRows(SessionIndex.of(attempts), expanded = false)
      .map(_.label)
    assertEquals(labels.count(_.contains("★")), 2)
    assert(labels.exists(_.contains("@aaaaaaaaaaaa")), labels.toString)
    assert(labels.exists(_.contains("@bbbbbbbbbbbb")), labels.toString)

  test("sessionRows across trees: rows on distinct branches carry no tree tag"):
    val attempts =
      List("aaaaaaaaaaaa" -> "feat-a", "bbbbbbbbbbbb" -> "feat-b").zipWithIndex
        .map:
          case ((hash, branch), i) =>
            ManifestFixtures.recorded(
              manifest(
                workDir = s"/repo/.orca/worktrees/$hash",
                branch = Some(branch),
                sessions =
                  List(durable(lastActiveAt = s"2026-07-18T1${i}:00:00Z"))
              ),
              crashed = false
            )
    assertEquals(
      SessionPicker
        .sessionRows(SessionIndex.of(attempts), expanded = false)
        .map(_.label),
      List(
        "★ main — latest (no stage yet) [claude] on feat-b",
        "★ main — latest (no stage yet) [claude] on feat-a"
      )
    )

  test("lineages differing only in their minting stage say which stage"):
    // Two per-task `implementer` sessions of one attempt: same name, same harness,
    // same (absent) last-active stage, one tree — the minting stage is all
    // there is to tell them apart.
    val attempts = List(
      ManifestFixtures.recorded(
        manifest(
          startedAt = "2026-07-18T09:00:00Z",
          sessions = List(
            durable(
              sessionName = "implementer",
              lastActiveAt = "2026-07-18T09:30:00Z",
              sessionStage = "Task: parse#0"
            ),
            durable(
              sessionName = "implementer",
              lastActiveAt = "2026-07-18T09:40:00Z",
              sessionStage = "Task: wire#0"
            )
          )
        ),
        crashed = false
      )
    )
    val labels = SessionPicker
      .sessionRows(SessionIndex.of(attempts), expanded = false)
      .map(_.label)
    assertEquals(labels.distinct.size, 2, labels.toString)
    assert(
      labels.exists(_.contains("(minted in Task: parse#0)")),
      labels.toString
    )
    assert(
      labels.exists(_.contains("(minted in Task: wire#0)")),
      labels.toString
    )

  test("a lineage nothing collides with does not print its minting stage"):
    val attempts = List(
      ManifestFixtures.recorded(
        manifest(
          startedAt = "2026-07-18T09:00:00Z",
          sessions = List(
            durable(
              sessionName = "implementer",
              lastActiveAt = "2026-07-18T09:30:00Z",
              sessionStage = "Task: parse#0"
            )
          )
        ),
        crashed = false
      )
    )
    val labels = SessionPicker
      .sessionRows(SessionIndex.of(attempts), expanded = false)
      .map(_.label)
    assert(!labels.exists(_.contains("minted in")), labels.toString)

  test("sessionListingRows carries the minting stage for scripts"):
    val attempts = List(
      ManifestFixtures.recorded(
        manifest(
          startedAt = "2026-07-18T09:00:00Z",
          sessions = List(
            durable(
              sessionName = "implementer",
              lastActiveAt = "2026-07-18T09:30:00Z",
              sessionStage = "Task: wire the parser#0"
            )
          )
        ),
        crashed = false
      )
    )
    assertEquals(
      Tables.sessionListingRows(SessionIndex.of(attempts)).head.sessionStage,
      Some("Task: wire the parser#0")
    )

  test(
    "successive runs in ONE directory still collapse into a single lineage"
  ):
    val attempts = List(
      ManifestFixtures.recorded(
        manifest(
          startedAt = "2026-07-18T09:00:00Z",
          sessions = List(
            durable(
              sessionName = "shared",
              lastActiveAt = "2026-07-18T09:30:00Z"
            )
          )
        ),
        crashed = false
      ),
      ManifestFixtures.recorded(
        manifest(
          startedAt = "2026-07-18T08:00:00Z",
          sessions = List(
            durable(
              sessionName = "shared",
              lastActiveAt = "2026-07-18T08:30:00Z"
            )
          )
        ),
        crashed = false
      )
    )
    val labels = SessionPicker
      .sessionRows(SessionIndex.of(attempts), expanded = false)
      .map(_.label)
    assertEquals(labels.count(_.contains("★")), 1)
    // One directory, so nothing to disambiguate.
    assert(!labels.exists(_.contains("@")), labels.toString)

  test("sessionListingRows gives each row the id continue <id> resolves"):
    val rows = Tables.sessionListingRows(SessionIndex.of(attemptsFixture()))
    assertEquals(
      rows.map(r => (r.id, r.sessionName)),
      List(
        (fixtureRef(0, 1), "newest"),
        (fixtureRef(1, 1), "older"),
        (fixtureRef(1, 2), "unresumable")
      )
    )
    assertEquals(rows.map(_.resumable), List(true, true, false))

  // --- resumeNotice: the resolved session's identity, printed before resuming ---

  test("resumeNotice: names the session, harness, and workDir"):
    val attempt = attemptsFixture().head
    val selection =
      ManifestFixtures.selection(
        attempt.manifest,
        attempt.manifest.sessions.head,
        crashed = false
      )
    assertEquals(
      SessionAction.resumeNotice(selection),
      "resuming session 'newest' [claude], on branch 'feature/newest', in /work"
    )

  test("resumeNotice: includes the stage when the session has one"):
    val attempt = attemptsFixture().head
    val withStage =
      attempt.manifest.sessions.head.copy(stage = Some("Task: fix a bug"))
    val selection =
      ManifestFixtures.selection(
        attempt.manifest,
        withStage,
        crashed = false
      )
    assertEquals(
      SessionAction.resumeNotice(selection),
      "resuming session 'newest' [claude], stage 'Task: fix a bug', on branch 'feature/newest', in /work"
    )

  test("resumeNotice: mentions a crashed attempt"):
    val attempt = attemptsFixture().head
    val selection =
      ManifestFixtures.selection(
        attempt.manifest,
        attempt.manifest.sessions.head,
        crashed = true
      )
    assertEquals(
      SessionAction.resumeNotice(selection),
      "resuming session 'newest' [claude], on branch 'feature/newest', in /work (crashed)"
    )

  // --- stdout/stderr separation (CLI review finding 1) ---

  /** A manifest missing the required `workDir` — the reader refuses it and
    * warns, which is what this test needs a warning for.
    */
  private def writeCorruptManifest(dir: os.Path): Unit =
    val json =
      """{
        |  "orcaVersion": "0.0.test",
        |  "pid": 1,
        |  "startedAt": "2026-07-18T09:00:00Z",
        |  "status": "Succeeded",
        |  "sessions": []
        |}""".stripMargin
    os.write(
      orca.OrcaDir.manifestPath(
        dir,
        orca.AttemptId(java.time.Instant.parse("2026-07-18T09:00:00Z"), 1)
      ),
      json,
      createFolders = true
    )

  test(
    "runContinue --list --json: a skipped-manifest warning lands on stderr, only JSON on stdout"
  ):
    val dir = TempDirs.dir()
    writeCorruptManifest(dir)
    val (out, err) = capturedBoth(
      assertEquals(
        ContinueCli
          .runContinue(
            ScanDirs(dir, Nil),
            None,
            list = true,
            json = true,
            tty = false
          ),
        ExitCodes.Ok
      )
    )
    assertEquals(out.trim, "[]")
    assert(err.contains("1784365200000-1.manifest.json"), err)

  private def writeCrashedManifest(dir: os.Path): Unit =
    writeManifest(
      dir,
      manifest(
        startedAt = "2026-07-18T09:00:00Z",
        pid = 999999,
        status = AttemptStatus.Running,
        sessions = List(durable(lastActiveAt = "2026-07-18T09:00:00Z"))
      )
    )

  private def writeSessionManifest(dir: os.Path, workDir: String): Unit =
    writeManifest(
      dir,
      manifest(
        workDir = workDir,
        startedAt = "2026-07-18T09:00:00Z",
        sessions = List(
          durable(
            sessionName = "implementer",
            lastActiveAt = "2026-07-18T09:00:00Z"
          )
        )
      )
    )

  test("runContinue --list: rows across worktrees say which tree each is in"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    // The same static flow session name in two trees — the suffix is the only
    // thing telling the user which one `orca continue <n>` reattaches to.
    writeSessionManifest(checkout, "/repo")
    writeSessionManifest(worktree, "/repo/.orca/worktrees/ab12cd34")
    val out = captured(
      assertEquals(
        ContinueCli.runContinue(
          ScanDirs(checkout, List(worktree)),
          None,
          list = true,
          json = false,
          tty = false
        ),
        ExitCodes.Ok
      )
    )
    assert(out.contains("implementer @repo"), out)
    assert(out.contains("implementer @ab12cd34"), out)

  test("runContinue --list: one directory, so no tree suffix on the row"):
    val checkout = TempDirs.dir()
    writeSessionManifest(checkout, "/repo")
    val out = captured(
      assertEquals(
        ContinueCli.runContinue(
          ScanDirs(checkout, Nil),
          None,
          list = true,
          json = false,
          tty = false
        ),
        ExitCodes.Ok
      )
    )
    assert(out.contains("implementer"), out)
    assert(!out.contains("@"), out)

  test("runContinue --list --json: each row carries its attempt's workDir"):
    val checkout = TempDirs.dir()
    writeSessionManifest(checkout, "/repo")
    val out = captured(
      assertEquals(
        ContinueCli.runContinue(
          ScanDirs(checkout, Nil),
          None,
          list = true,
          json = true,
          tty = false
        ),
        ExitCodes.Ok
      )
    )
    assert(out.contains("\"workDir\":\"/repo\""), out)

  private def listedWithBranch(
      branch: Option[String],
      json: Boolean
  ): String =
    val dir = TempDirs.dir()
    writeManifest(
      dir,
      manifest(sessions = List(durable()), branch = branch)
    )
    captured(
      assertEquals(
        ContinueCli.runContinue(
          ScanDirs(dir, Nil),
          None,
          list = true,
          json = json,
          tty = false
        ),
        ExitCodes.Ok
      )
    )

  test("runContinue --list --json: a row carries its attempt's branch"):
    val out = listedWithBranch(Some("orca-fix-parser"), json = true)
    assert(out.contains("\"branch\":\"orca-fix-parser\""), out)

  test("runContinue --list --json: an attempt with no branch reports null"):
    val out = listedWithBranch(None, json = true)
    assert(out.contains("\"branch\":null"), out)

  test("runContinue --list: the table has a branch column"):
    val out = listedWithBranch(Some("orca-fix-parser"), json = false)
    val lines = out.linesIterator.toList
    assert(lines.head.contains("branch"), out)
    assert(lines(1).contains("orca-fix-parser"), out)

  test("runContinue --list --json: kind is Durable or Ephemeral"):
    val dir = TempDirs.dir()
    writeManifest(
      dir,
      manifest(
        startedAt = "2026-07-18T09:00:00Z",
        sessions = List(durable(), ephemeral(agent = "reviewer"))
      )
    )
    val out = captured(
      assertEquals(
        ContinueCli.runContinue(
          ScanDirs(dir, Nil),
          None,
          list = true,
          json = true,
          tty = false
        ),
        ExitCodes.Ok
      )
    )
    assert(out.contains("\"kind\":\"Durable\""), out)
    assert(out.contains("\"kind\":\"Ephemeral\""), out)

  test("runContinue --list --json: a crashed attempt reports crashed=true"):
    val dir = TempDirs.dir()
    writeCrashedManifest(dir)
    val out = captured(
      assertEquals(
        ContinueCli
          .runContinue(
            ScanDirs(dir, Nil),
            None,
            list = true,
            json = true,
            tty = false
          ),
        ExitCodes.Ok
      )
    )
    assert(out.contains("\"crashed\":true"), out)

  test(
    "runContinue --list: a crashed attempt's table row is suffixed (crashed)"
  ):
    val dir = TempDirs.dir()
    writeCrashedManifest(dir)
    val out = captured(
      assertEquals(
        ContinueCli
          .runContinue(
            ScanDirs(dir, Nil),
            None,
            list = true,
            json = false,
            tty = false
          ),
        ExitCodes.Ok
      )
    )
    assert(out.contains("main (crashed)"), out)

  test(
    "runContinue: no selector prints the resume notice to stderr before attempting to resume"
  ):
    val dir = TempDirs.dir()
    val goneWorkDir = (dir / "gone").toString
    writeManifest(
      dir,
      manifest(
        workDir = goneWorkDir,
        startedAt = "2026-07-18T09:00:00Z",
        sessions = List(
          durable(
            stage = Some("Task: fix a bug"),
            lastActiveAt = "2026-07-18T09:00:00Z"
          )
        )
      )
    )
    val (out, err) = capturedBoth(
      assertEquals(
        ContinueCli
          .runContinue(
            ScanDirs(dir, Nil),
            None,
            list = false,
            json = false,
            tty = true
          ),
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
