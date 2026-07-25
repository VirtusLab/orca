package orca.shell

import org.jline.terminal.{Terminal, TerminalBuilder}
import orca.StackSettings
import orca.runner.{ManifestSession, RunManifest}
import orca.settings.SettingsFile
import orca.shell.actions.StackAction
import orca.shell.create.CreateTier
import orca.shell.flows.{DiscoveredFlow, FlowOrigin}
import orca.shell.sessions.{RecordedRun, SessionPicker, SessionSelection}
import orca.shell.ui.{Choice, ShellUi, UiOutcome}
import orca.testkit.TempDirs

/** Answers a single fixed `confirm` outcome, recording the question it was
  * asked; every other prompt is unsupported — [[Main.rediscoverStack]] and
  * [[Main.promptCreateBranch]] only ever call `confirm`.
  */
private class ConfirmOnlyUi(outcome: UiOutcome[Boolean]) extends ShellUi:
  var recordedQuestion: Option[String] = None
  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    throw new UnsupportedOperationException("rediscoverStack doesn't select")
  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    recordedQuestion = Some(question)
    outcome
  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    throw new UnsupportedOperationException("rediscoverStack doesn't input")
  def inputMultiline(prompt: String): UiOutcome[String] =
    throw new UnsupportedOperationException("rediscoverStack doesn't input")

/** Records every `select` call's shown choices (in shown order) and always
  * answers with the fixed `outcome` — used to verify [[Main.pickFlow]] hands
  * `ui.select` the ALREADY-reordered list, not just that the pure
  * `promoteByName`/`reorder` helper computes the right order in isolation.
  * `confirm`/`input` are unsupported: `pickFlow` never calls them.
  */
private class RecordingSelectUi(outcome: UiOutcome[DiscoveredFlow])
    extends ShellUi:
  private var shown: List[List[Choice[DiscoveredFlow]]] = Nil
  def recordedChoices: List[List[Choice[DiscoveredFlow]]] = shown
  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    shown = shown :+ choices.asInstanceOf[List[Choice[DiscoveredFlow]]]
    outcome.asInstanceOf[UiOutcome[A]]
  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    throw new UnsupportedOperationException("pickFlow doesn't confirm")
  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    throw new UnsupportedOperationException("pickFlow doesn't input")
  def inputMultiline(prompt: String): UiOutcome[String] =
    throw new UnsupportedOperationException("pickFlow doesn't input")

/** Counts calls to `select`/`inputMultiline`/`input` and replays queued
  * outcomes for each — used to verify `Main.createNewFlow`/`createForkFlow`
  * stop asking anything the moment a prompt is cancelled, in particular that no
  * further harness/model/yolo prompt follows (authoring no longer has one).
  * `confirm` is unsupported: neither method calls it.
  */
private class FlowScriptedUi(
    selectScript: List[UiOutcome[Any]] = Nil,
    inputMultilineScript: List[UiOutcome[String]] = Nil,
    inputScript: List[UiOutcome[String]] = Nil
) extends ShellUi:
  private var pendingSelect = selectScript
  private var pendingInputMultiline = inputMultilineScript
  private var pendingInput = inputScript
  var selectCount = 0
  var inputMultilineCount = 0
  var inputCount = 0

  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    selectCount += 1
    val outcome = pendingSelect.head
    pendingSelect = pendingSelect.tail
    outcome.asInstanceOf[UiOutcome[A]]

  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    throw new UnsupportedOperationException(
      "createNewFlow/createForkFlow don't confirm"
    )

  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    inputCount += 1
    val outcome = pendingInput.head
    pendingInput = pendingInput.tail
    outcome

  def inputMultiline(prompt: String): UiOutcome[String] =
    inputMultilineCount += 1
    val outcome = pendingInputMultiline.head
    pendingInputMultiline = pendingInputMultiline.tail
    outcome

class MainTest extends munit.FunSuite:

  private def manifest(
      workDir: String = "/work",
      startedAt: String = "2026-07-18T10:00:00Z",
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
      agent: String = "main",
      sessionName: String = "main",
      stage: Option[String] = None,
      lastActiveAt: String = "2026-07-18T10:00:00Z",
      harness: String = "ClaudeCode",
      wireId: Option[String] = Some("uuid"),
      reason: Option[String] = None
  ): ManifestSession =
    ManifestSession(
      harness = harness,
      wireId = wireId,
      resumable = wireId.isDefined,
      reason = reason,
      agent = agent,
      role = None,
      stage = stage,
      sessionName = Some(sessionName),
      kind = "durable",
      firstSeenAt = lastActiveAt,
      lastActiveAt = lastActiveAt
    )

  private def oneShot(
      agent: String = "main",
      role: Option[String] = None,
      stage: Option[String] = None,
      lastActiveAt: String = "2026-07-18T10:00:00Z",
      harness: String = "ClaudeCode",
      wireId: Option[String] = Some("uuid"),
      reason: Option[String] = None
  ): ManifestSession =
    ManifestSession(
      harness = harness,
      wireId = wireId,
      resumable = wireId.isDefined,
      reason = reason,
      agent = agent,
      role = role,
      stage = stage,
      sessionName = None,
      kind = "oneShot",
      firstSeenAt = lastActiveAt,
      lastActiveAt = lastActiveAt
    )

  private def resumeSelections(
      rows: List[orca.shell.ui.Choice[SessionPicker.PickerRow]]
  ): List[SessionSelection] =
    rows.collect {
      case orca.shell.ui.Choice(SessionPicker.PickerRow.Resume(s), _, _) =>
        s
    }

  // -- Realistic mixed fixture (a representative session mix): a "main" coder
  // lineage resumed/re-run across three separate flow runs (so three
  // occurrences, newest last-active wins), a Plan-stage one-shot, and three
  // reviewer one-shots.
  private def mixedRuns(): List[RecordedRun] =
    val run1 = RecordedRun(
      manifest(
        startedAt = "2026-07-16T09:00:00Z",
        sessions = List(
          durable(stage = Some("Plan"), lastActiveAt = "2026-07-16T09:05:00Z"),
          oneShot(
            role = None,
            stage = Some("Plan"),
            lastActiveAt = "2026-07-16T09:01:00Z"
          )
        )
      ),
      crashed = false
    )
    val run2 = RecordedRun(
      manifest(
        startedAt = "2026-07-17T09:00:00Z",
        sessions = List(
          durable(
            stage = Some("Task: add auth"),
            lastActiveAt = "2026-07-17T09:30:00Z"
          ),
          oneShot(
            agent = "code-structure",
            role = Some("reviewer"),
            stage = Some("Task: add auth"),
            lastActiveAt = "2026-07-17T09:20:00Z"
          ),
          oneShot(
            agent = "test-coverage",
            role = Some("reviewer"),
            stage = Some("Task: add auth"),
            lastActiveAt = "2026-07-17T09:21:00Z"
          )
        )
      ),
      crashed = false
    )
    val run3 = RecordedRun(
      manifest(
        startedAt = "2026-07-18T09:00:00Z",
        sessions = List(
          durable(
            stage = Some("Task: fix bug"),
            lastActiveAt = "2026-07-18T09:45:00Z"
          ),
          oneShot(
            agent = "security",
            role = Some("reviewer"),
            stage = Some("Task: fix bug"),
            lastActiveAt = "2026-07-18T09:40:00Z"
          )
        )
      ),
      crashed = false
    )
    List(run3, run2, run1) // newest-run-first, as ManifestReader.list returns

  test(
    "sessionRows (collapsed): shows only the newest durable occurrence, starred"
  ):
    val rows = SessionPicker.sessionRows(mixedRuns(), expanded = false)
    val resumes = resumeSelections(rows)
    assertEquals(resumes.map(_.session.stage), List(Some("Task: fix bug")))

  test(
    "sessionRows (collapsed): render shape is the starred row plus both expander labels"
  ):
    val rows = SessionPicker.sessionRows(mixedRuns(), expanded = false)
    assertEquals(
      rows.map(_.label),
      List(
        "★ main — latest (stage: Task: fix bug) [claude]",
        "… show 2 earlier occurrences",
        "… show 4 one-shot sessions (reviews, plan steps)"
      )
    )

  test(
    "sessionRows (collapsed): one-shots and earlier occurrences are hidden behind expanders"
  ):
    val rows = SessionPicker.sessionRows(mixedRuns(), expanded = false)
    assertEquals(rows.size, 3)
    assertEquals(rows(1).value, SessionPicker.PickerRow.ShowMore)
    assertEquals(rows(2).value, SessionPicker.PickerRow.ShowMore)

  test(
    "sessionRows (expanded): reveals earlier occurrences and one-shots, no expander rows"
  ):
    val rows = SessionPicker.sessionRows(mixedRuns(), expanded = true)
    assert(!rows.exists(_.value == SessionPicker.PickerRow.ShowMore))
    // 1 starred + 2 earlier occurrences + 4 one-shots
    assertEquals(rows.size, 7)

  test(
    "sessionRows (expanded): earlier occurrences and one-shots are each sorted newest-first"
  ):
    val rows = SessionPicker.sessionRows(mixedRuns(), expanded = true)
    val resumes = resumeSelections(rows)
    val stages = resumes.map(_.session.stage.getOrElse(""))
    // starred row (fix bug) is first; then earlier occurrences (auth, then
    // plan, newest first); then one-shots (security, test-coverage,
    // code-structure, plan) newest first.
    assertEquals(
      stages,
      List(
        "Task: fix bug",
        "Task: add auth",
        "Plan",
        "Task: fix bug",
        "Task: add auth",
        "Task: add auth",
        "Plan"
      )
    )

  test(
    "sessionRows (expanded): earlier-occurrence rows are labeled with the session name and an (earlier occurrence) marker"
  ):
    val run1 = RecordedRun(
      manifest(
        startedAt = "2026-07-17T09:00:00Z",
        sessions = List(
          durable(stage = Some("Plan"), lastActiveAt = "2026-07-17T09:05:00Z")
        )
      ),
      crashed = false
    )
    val run2 = RecordedRun(
      manifest(
        startedAt = "2026-07-18T09:00:00Z",
        sessions = List(
          durable(stage = Some("Task"), lastActiveAt = "2026-07-18T09:05:00Z")
        )
      ),
      crashed = false
    )
    val rows = SessionPicker.sessionRows(List(run2, run1), expanded = true)
    assertEquals(
      rows.map(_.label),
      List(
        "★ main — latest (stage: Task) [claude]",
        "main — stage Plan [claude] (earlier occurrence)"
      )
    )

  test(
    "sessionRows (expanded): one-shot rows are labeled with agent, role, stage and an (one-shot) marker"
  ):
    val run = RecordedRun(
      manifest(sessions =
        List(
          oneShot(
            agent = "code-structure",
            role = Some("reviewer"),
            stage = Some("Task: add auth")
          )
        )
      ),
      crashed = false
    )
    assertEquals(
      SessionPicker.sessionRows(List(run), expanded = true).map(_.label),
      List(
        "code-structure (reviewer) — stage Task: add auth [claude] (one-shot)"
      )
    )

  test(
    "sessionRows omits the earlier-occurrences expander when there's only one occurrence"
  ):
    val run = RecordedRun(
      manifest(sessions = List(durable())),
      crashed = false
    )
    assertEquals(
      SessionPicker.sessionRows(List(run), expanded = false).map(_.label),
      List("★ main — latest (no stage yet) [claude]")
    )

  test("sessionRows singularises a count of 1 in the expander label"):
    val run = RecordedRun(
      manifest(sessions = List(durable(), oneShot())),
      crashed = false
    )
    assertEquals(
      SessionPicker.sessionRows(List(run), expanded = false).map(_.label),
      List(
        "★ main — latest (no stage yet) [claude]",
        "… show 1 one-shot session (reviews, plan steps)"
      )
    )

  test(
    "sessionRows groups durable lineages by (agent, sessionName), not agent alone"
  ):
    val run = RecordedRun(
      manifest(sessions =
        List(
          durable(
            agent = "coder",
            sessionName = "main",
            lastActiveAt = "2026-07-18T09:00:00Z"
          ),
          durable(
            agent = "coder",
            sessionName = "helper",
            lastActiveAt = "2026-07-18T09:05:00Z"
          )
        )
      ),
      crashed = false
    )
    val rows = SessionPicker.sessionRows(List(run), expanded = false)
    assertEquals(
      rows.map(_.label),
      List(
        "★ helper — latest (no stage yet) [claude]",
        "★ main — latest (no stage yet) [claude]"
      )
    )

  test("sessionRows suffixes a crashed run's rows with `(crashed)`"):
    val run = RecordedRun(manifest(sessions = List(durable())), crashed = true)
    assertEquals(
      SessionPicker.sessionRows(List(run), expanded = false).map(_.label),
      List("★ main — latest (no stage yet) [claude] (crashed)")
    )

  test(
    "sessionRows falls back to the raw harness string for an unrecognised one"
  ):
    val run = RecordedRun(
      manifest(sessions = List(durable(harness = "SomeFutureHarness"))),
      crashed = false
    )
    assertEquals(
      SessionPicker.sessionRows(List(run), expanded = false).map(_.label),
      List("★ main — latest (no stage yet) [SomeFutureHarness]")
    )

  test("sessionRows disables a wireId-less session with its stored reason"):
    val reason = "pi sessions are deleted when the run's temp dir is reclaimed"
    val run = RecordedRun(
      manifest(sessions =
        List(durable(harness = "Pi", wireId = None, reason = Some(reason)))
      ),
      crashed = false
    )
    assertEquals(
      SessionPicker
        .sessionRows(List(run), expanded = false)
        .map(_.disabledReason),
      List(Some(reason))
    )

  test("sessionRows enables a claude session with a wireId"):
    val run = RecordedRun(manifest(sessions = List(durable())), crashed = false)
    assertEquals(
      SessionPicker
        .sessionRows(List(run), expanded = false)
        .map(_.disabledReason),
      List(None)
    )

  test(
    "sessionRows is a silent no-op shape on an empty run list (no rows, no crash)"
  ):
    assertEquals(SessionPicker.sessionRows(Nil, expanded = false), Nil)
    assertEquals(SessionPicker.sessionRows(Nil, expanded = true), Nil)

  // --- promoteByName ---

  private def flow(name: String): DiscoveredFlow =
    DiscoveredFlow(
      name = name,
      description = None,
      origin = FlowOrigin.BuiltIn,
      path = os.root / s"$name",
      shadows = Nil
    )

  test("promoteByName moves the named flow to the front, rest stay ordered"):
    val flows = List(flow("alpha.sc"), flow("implement.sc"), flow("zeta.sc"))
    assertEquals(
      Main.promoteByName("implement.sc", flows).map(_.name),
      List("implement.sc", "alpha.sc", "zeta.sc")
    )

  test("promoteByName is a no-op when the name isn't in the list"):
    val flows = List(flow("alpha.sc"), flow("zeta.sc"))
    assertEquals(
      Main.promoteByName("implement.sc", flows).map(_.name),
      List("alpha.sc", "zeta.sc")
    )

  test("promoteByName on an empty list stays empty"):
    assertEquals(Main.promoteByName("implement.sc", Nil), Nil)

  // --- pickFlow: end-to-end, ui.select actually receives the reordered list ---

  private val threeFlows =
    List(flow("alpha.sc"), flow("implement.sc"), flow("zeta.sc"))

  test(
    "pickFlow: the run picker's reorder promotes implement.sc to the front of what ui.select shows"
  ):
    val ui = new RecordingSelectUi(UiOutcome.Cancelled)
    val _ =
      Main.pickFlow(
        ui,
        "Run which flow?",
        threeFlows,
        reorder = Main.promoteByName(Main.FlagshipFlow, _)
      )
    assertEquals(
      ui.recordedChoices.head.map(_.value.name),
      List("implement.sc", "alpha.sc", "zeta.sc")
    )

  test(
    "pickFlow: view/edit pickers (no reorder given) stay alphabetical"
  ):
    val ui = new RecordingSelectUi(UiOutcome.Cancelled)
    val _ = Main.pickFlow(ui, "View which flow?", threeFlows)
    assertEquals(
      ui.recordedChoices.head.map(_.value.name),
      List("alpha.sc", "implement.sc", "zeta.sc")
    )

  // --- promptCreateBranch (the branch-creation confirm before a run) ---

  test("promptCreateBranch: the question explains what declining does"):
    val ui = ConfirmOnlyUi(UiOutcome.Selected(true))
    assertEquals(Main.promptCreateBranch(ui), Some(true))
    assertEquals(
      ui.recordedQuestion,
      Some(
        "Create a new branch for this run? (choosing 'no': the flow makes " +
          "its changes on the current branch)"
      )
    )

  test(
    "promptCreateBranch: confirming (Enter's default) keeps normal branch-creating behavior"
  ):
    assertEquals(
      Main.promptCreateBranch(ConfirmOnlyUi(UiOutcome.Selected(true))),
      Some(true)
    )

  test(
    "promptCreateBranch: declining selects skip-branch mode (caller negates to skipBranch = true)"
  ):
    assertEquals(
      Main.promptCreateBranch(ConfirmOnlyUi(UiOutcome.Selected(false))),
      Some(false)
    )

  test("promptCreateBranch: cancelling aborts the run"):
    assertEquals(
      Main.promptCreateBranch(ConfirmOnlyUi(UiOutcome.Cancelled)),
      None
    )

  // --- createNewFlow / createForkFlow: cancelling stops immediately, with no
  // harness/model/yolo prompt following (authoring no longer has one — it
  // runs the built-in flow with the configured role agents automatically).
  // The launch itself (AuthorAction) is exercised separately in
  // AuthorActionTest with an injected launcher; these only cover the
  // menu-side prompting, up to the point where a real prompt is cancelled.

  private def withDumbTerminal(body: Terminal => Unit): Unit =
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try body(terminal)
    finally terminal.close()

  test("createNewFlow: cancelling the tier prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript = List(UiOutcome.Cancelled))
      Main.createNewFlow(ui, terminal)
      assertEquals(ui.selectCount, 1)
      assertEquals(ui.inputMultilineCount, 0)
      assertEquals(ui.inputCount, 0)

  test(
    "createNewFlow: cancelling the goal prompt stops before the filename prompt"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(
        selectScript = List(UiOutcome.Selected(CreateTier.Project)),
        inputMultilineScript = List(UiOutcome.Cancelled)
      )
      Main.createNewFlow(ui, terminal)
      assertEquals(ui.selectCount, 1)
      assertEquals(ui.inputMultilineCount, 1)
      assertEquals(ui.inputCount, 0)

  test("createForkFlow: cancelling the source prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript = List(UiOutcome.Cancelled))
      Main.createForkFlow(ui, terminal)
      assertEquals(ui.selectCount, 1)
      assertEquals(ui.inputMultilineCount, 0)
      assertEquals(ui.inputCount, 0)

  test(
    "createForkFlow: cancelling the changes prompt stops before the tier prompt"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(
        selectScript = List(UiOutcome.Selected(flow("implement.sc"))),
        inputMultilineScript = List(UiOutcome.Cancelled)
      )
      Main.createForkFlow(ui, terminal)
      assertEquals(ui.selectCount, 1)
      assertEquals(ui.inputMultilineCount, 1)
      assertEquals(ui.inputCount, 0)

  test(
    "createForkFlow: cancelling the tier prompt is the last stop before authoring would launch"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(
        selectScript =
          List(UiOutcome.Selected(flow("implement.sc")), UiOutcome.Cancelled),
        inputMultilineScript = List(UiOutcome.Selected("add a retry step"))
      )
      Main.createForkFlow(ui, terminal)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputMultilineCount, 1)
      assertEquals(ui.inputCount, 0)

  // --- editSettings ---
  //
  // `spawnEditor` is injected instead of `EditAction.editInPlace` (a real
  // subprocess seam, like `AuthorAction`'s injected `FlowLaunch`) so these
  // tests never spawn a real editor: the fake stands in for "the editor ran
  // and exited", optionally rewriting the file first to simulate what the
  // user did inside it.

  test("editSettings: cancelling the tier prompt spawns no editor"):
    withDumbTerminal: terminal =>
      var spawnCount = 0
      val ui = FlowScriptedUi(selectScript = List(UiOutcome.Cancelled))
      Main.editSettings(
        ui,
        terminal,
        TempDirs.dir() / "settings.properties",
        workDir = TempDirs.dir(),
        spawnEditor = (_, _) => { spawnCount += 1; 0 }
      )
      assertEquals(spawnCount, 0)

  test(
    "editSettings: Project — an absent file is created from the template before the editor opens it"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(CreateTier.Project))
      )
      var editedPath: Option[os.Path] = None
      Main.editSettings(
        ui,
        terminal,
        TempDirs.dir() / "settings.properties",
        workDir = workDir,
        spawnEditor = (_, path) => { editedPath = Some(path); 0 }
      )
      val expected = workDir / ".orca" / "settings.properties"
      assertEquals(editedPath, Some(expected))
      assertEquals(os.read(expected), SettingsFile.render(Nil))

  test(
    "editSettings: Global — an absent file is created from the template before the editor opens it"
  ):
    withDumbTerminal: terminal =>
      val globalPath = TempDirs.dir() / "settings.properties"
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(CreateTier.Global))
      )
      Main.editSettings(
        ui,
        terminal,
        globalPath,
        workDir = TempDirs.dir(),
        spawnEditor = (_, _) => 0
      )
      assert(os.exists(globalPath))

  test(
    "editSettings: a valid edit reprints the config summary"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      val globalPath = TempDirs.dir() / "settings.properties"
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(CreateTier.Global))
      )
      val out = captured(
        Main.editSettings(
          ui,
          terminal,
          globalPath,
          workDir = workDir,
          spawnEditor = (_, _) => 0
        )
      )
      assert(out.contains("agents:"), out)
      assert(out.contains("stack:"), out)

  test(
    "editSettings: a malformed edit prints a warning instead of crashing, without reprinting the summary"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      val globalPath = TempDirs.dir() / "settings.properties"
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(CreateTier.Global))
      )
      val out = captured(
        Main.editSettings(
          ui,
          terminal,
          globalPath,
          workDir = workDir,
          spawnEditor = (_, path) => {
            os.write.over(path, "not a valid line\n")
            0
          }
        )
      )
      assert(out.contains("malformed"), out)
      assert(!out.contains("agents:"), out)

  // --- rediscoverStack ---

  private def captured(body: => Unit): String =
    val buffer = new java.io.ByteArrayOutputStream()
    Console.withOut(new java.io.PrintStream(buffer))(body)
    buffer.toString

  test(
    "rediscoverStack is a no-op, without creating .orca, when the settings file is absent"
  ):
    val dir = TempDirs.dir()
    val out =
      captured(Main.rediscoverStack(ConfirmOnlyUi(UiOutcome.Cancelled), dir))
    assert(!os.exists(dir / ".orca"))
    assert(
      out.contains("no stack settings to clear"),
      s"should explain there's nothing to clear: $out"
    )

  test(
    "rediscoverStack is a no-op, leaving the file untouched, when it has no stack lines"
  ):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content =
      "# orca settings — edit freely, commit with the project.\ncodingAgent = codex\n"
    os.write.over(path, content)
    val out =
      captured(Main.rediscoverStack(ConfirmOnlyUi(UiOutcome.Cancelled), dir))
    assertEquals(os.read(path), content)
    assert(
      out.contains("no stack settings to clear"),
      s"should explain there's nothing to clear: $out"
    )

  test("rediscoverStack aborts on a malformed settings file without writing"):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content = "format = cargo fmt\nnotAKey = whatever\n"
    os.write.over(path, content)
    val out =
      captured(Main.rediscoverStack(ConfirmOnlyUi(UiOutcome.Cancelled), dir))
    assertEquals(os.read(path), content)
    assert(
      out.contains("invalid settings"),
      s"should abort with the parse error: $out"
    )

  test(
    "rediscoverStack strips stack lines and writes back when the user confirms"
  ):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content =
      "# orca settings — edit freely, commit with the project.\n" +
        "# Delete the stack lines (format/lint/test, commented ones too) to re-run auto-discovery.\n" +
        "format = cargo fmt\n" +
        "codingAgent = codex\n"
    os.write.over(path, content)
    Main.rediscoverStack(ConfirmOnlyUi(UiOutcome.Selected(true)), dir)
    val rewritten = os.read(path)
    assertEquals(rewritten, SettingsFile.stripStackLines(content))
    assert(!SettingsFile.hasStackLines(rewritten))

  test("rediscoverStack leaves the file untouched when the user declines"):
    val dir = TempDirs.dir()
    os.makeDir.all(dir / ".orca")
    val path = dir / ".orca" / "settings.properties"
    val content =
      "# orca settings — edit freely, commit with the project.\n" +
        "format = cargo fmt\n"
    os.write.over(path, content)
    Main.rediscoverStack(ConfirmOnlyUi(UiOutcome.Selected(false)), dir)
    assertEquals(os.read(path), content)

  // --- renderStackSettings ---

  test(
    "renderStackSettings lists each non-empty key in format/lint/test order"
  ):
    assertEquals(
      StackAction.renderStackSettings(
        StackSettings(
          format = List("cargo fmt"),
          lint = List("cargo check --tests"),
          test = List("cargo test")
        )
      ),
      "  format: cargo fmt\n  lint: cargo check --tests\n  test: cargo test"
    )

  test("renderStackSettings notes when there are no live commands"):
    assert(
      StackAction
        .renderStackSettings(StackSettings.empty)
        .contains("no live commands")
    )

  // --- printConfigSummary ---

  test(
    "printConfigSummary prints the agents line then the stack line, both shell-voice"
  ):
    val globalDir = TempDirs.dir()
    val workDir = TempDirs.dir()
    val out = captured(
      Main.printConfigSummary(globalDir / "settings.properties", workDir)
    )
    assertEquals(
      out.linesIterator.toList,
      List(
        "◆ agents: planning=claude, coding=claude, review=claude",
        "◆ stack: not discovered yet — detected on the first flow run"
      )
    )
