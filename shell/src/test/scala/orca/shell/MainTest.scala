package orca.shell

import org.jline.terminal.{Terminal, TerminalBuilder}
import orca.{OrcaArgs, RunTarget, StackSettings, Uncommitted}
import orca.agents.BackendTag
import orca.settings.SettingsFile
import orca.shell.actions.{SettingsEditAction, StackAction}
import orca.shell.create.CreateTier
import orca.discovery.Origin
import orca.shell.flows.DiscoveredFlow
import orca.shell.resume.InterruptedRun
import orca.shell.run.LaunchResult
import orca.shell.sessions.{RecordedAttempt, SessionPicker, SessionSelection}
import orca.shell.sessions.ManifestFixtures.{durable, ephemeral, manifest}
import orca.shell.ui.{Choice, ShellUi, UiOutcome}
import orca.testkit.TempDirs

/** Answers a single fixed `confirm` outcome, recording the question it was
  * asked and the default offered; every other prompt is unsupported —
  * [[Main.rediscoverStack]] only ever calls `confirm`.
  */
private class ConfirmOnlyUi(outcome: UiOutcome[Boolean]) extends ShellUi:
  var recordedQuestion: Option[String] = None
  var recordedDefault: Option[Boolean] = None
  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    throw new UnsupportedOperationException("rediscoverStack doesn't select")
  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    recordedQuestion = Some(question)
    recordedDefault = Some(default)
    outcome
  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    throw new UnsupportedOperationException("rediscoverStack doesn't input")
  def inputMultiline(prompt: String): UiOutcome[String] =
    throw new UnsupportedOperationException("rediscoverStack doesn't input")

/** Records every `select` call's shown choices (in shown order) and the
  * preselection offered, and always answers with the fixed `outcome` — used to
  * verify [[Main.pickFlow]] hands `ui.select` the ALREADY-reordered list (not
  * just that the pure `promoteByName`/`reorder` helper computes the right order
  * in isolation), and that [[Main.promptRunTarget]] offers all three
  * destinations with the default preselected. `confirm`/`input` are
  * unsupported: neither caller uses them.
  */
private class RecordingSelectUi[T](outcome: UiOutcome[T]) extends ShellUi:
  private var shown: List[List[Choice[T]]] = Nil
  private var preselected: List[Option[T]] = Nil
  def recordedChoices: List[List[Choice[T]]] = shown
  def recordedPreselect: Option[Option[T]] = preselected.headOption
  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    shown = shown :+ choices.asInstanceOf[List[Choice[T]]]
    preselected = preselected :+ preselect.asInstanceOf[Option[T]]
    outcome.asInstanceOf[UiOutcome[A]]
  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    throw new UnsupportedOperationException("neither caller confirms")
  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    throw new UnsupportedOperationException("neither caller inputs")
  def inputMultiline(prompt: String): UiOutcome[String] =
    throw new UnsupportedOperationException("neither caller inputs")

/** Counts calls to `select`/`inputMultiline`/`input` and replays queued
  * outcomes for each — used to verify `Main.createNewFlow`/`createForkFlow`
  * stop asking anything the moment a prompt is cancelled, in particular that no
  * further harness/model/yolo prompt follows (authoring no longer has one).
  * `confirm` is unsupported: neither method calls it.
  */
private class FlowScriptedUi(
    selectScript: List[UiOutcome[Any]] = Nil,
    inputMultilineScript: List[UiOutcome[String]] = Nil,
    inputScript: List[UiOutcome[String]] = Nil,
    confirmScript: List[UiOutcome[Boolean]] = Nil
) extends ShellUi:
  private var pendingSelect = selectScript
  private var pendingInputMultiline = inputMultilineScript
  private var pendingInput = inputScript
  private var pendingConfirm = confirmScript
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
    if pendingConfirm.isEmpty then
      throw new UnsupportedOperationException(
        "createNewFlow/createForkFlow don't confirm"
      )
    val outcome = pendingConfirm.head
    pendingConfirm = pendingConfirm.tail
    outcome

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

  private def resumeSelections(
      rows: List[orca.shell.ui.Choice[SessionPicker.PickerRow]]
  ): List[SessionSelection] =
    rows.collect {
      case orca.shell.ui.Choice(SessionPicker.PickerRow.Resume(s), _, _) =>
        s
    }

  // -- Realistic mixed fixture (a representative session mix): a "main" coder
  // lineage resumed/re-run across three attempts (so three occurrences, newest
  // last-active wins), a Plan-stage ephemeral, and three reviewer ephemeral
  // sessions.
  private def mixedAttempts(): List[RecordedAttempt] =
    val attempt1 = RecordedAttempt(
      manifest(
        startedAt = "2026-07-16T09:00:00Z",
        sessions = List(
          durable(stage = Some("Plan"), lastActiveAt = "2026-07-16T09:05:00Z"),
          ephemeral(
            role = None,
            stage = Some("Plan"),
            lastActiveAt = "2026-07-16T09:01:00Z"
          )
        )
      ),
      crashed = false
    )
    val attempt2 = RecordedAttempt(
      manifest(
        startedAt = "2026-07-17T09:00:00Z",
        sessions = List(
          durable(
            stage = Some("Task: add auth"),
            lastActiveAt = "2026-07-17T09:30:00Z"
          ),
          ephemeral(
            agent = "code-structure",
            role = Some("reviewer"),
            stage = Some("Task: add auth"),
            lastActiveAt = "2026-07-17T09:20:00Z"
          ),
          ephemeral(
            agent = "test-coverage",
            role = Some("reviewer"),
            stage = Some("Task: add auth"),
            lastActiveAt = "2026-07-17T09:21:00Z"
          )
        )
      ),
      crashed = false
    )
    val attempt3 = RecordedAttempt(
      manifest(
        startedAt = "2026-07-18T09:00:00Z",
        sessions = List(
          durable(
            stage = Some("Task: fix bug"),
            lastActiveAt = "2026-07-18T09:45:00Z"
          ),
          ephemeral(
            agent = "security",
            role = Some("reviewer"),
            stage = Some("Task: fix bug"),
            lastActiveAt = "2026-07-18T09:40:00Z"
          )
        )
      ),
      crashed = false
    )
    List(
      attempt3,
      attempt2,
      attempt1
    ) // newest first, as ManifestReader.list returns

  test(
    "sessionRows (collapsed): shows only the newest durable occurrence, starred"
  ):
    val rows = SessionPicker.sessionRows(mixedAttempts(), expanded = false)
    val resumes = resumeSelections(rows)
    assertEquals(resumes.map(_.session.stage), List(Some("Task: fix bug")))

  test(
    "sessionRows (collapsed): render shape is the starred row plus both expander labels"
  ):
    val rows = SessionPicker.sessionRows(mixedAttempts(), expanded = false)
    assertEquals(
      rows.map(_.label),
      List(
        "★ main — latest (stage: Task: fix bug) [claude]",
        "… show 2 earlier occurrences",
        "… show 4 ephemeral sessions (reviews, plan steps)"
      )
    )

  test(
    "sessionRows (collapsed): ephemeral sessions and earlier occurrences are hidden behind expanders"
  ):
    val rows = SessionPicker.sessionRows(mixedAttempts(), expanded = false)
    assertEquals(rows.size, 3)
    assertEquals(rows(1).value, SessionPicker.PickerRow.ShowMore)
    assertEquals(rows(2).value, SessionPicker.PickerRow.ShowMore)

  test(
    "sessionRows (expanded): reveals earlier occurrences and ephemeral sessions, no expander rows"
  ):
    val rows = SessionPicker.sessionRows(mixedAttempts(), expanded = true)
    assert(!rows.exists(_.value == SessionPicker.PickerRow.ShowMore))
    // 1 starred + 2 earlier occurrences + 4 ephemeral sessions
    assertEquals(rows.size, 7)

  test(
    "sessionRows (expanded): earlier occurrences and ephemeral sessions are each sorted newest-first"
  ):
    val rows = SessionPicker.sessionRows(mixedAttempts(), expanded = true)
    val resumes = resumeSelections(rows)
    val stages = resumes.map(_.session.stage.getOrElse(""))
    // starred row (fix bug) is first; then earlier occurrences (auth, then
    // plan, newest first); then ephemeral sessions (security, test-coverage,
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
    val attempt1 = RecordedAttempt(
      manifest(
        startedAt = "2026-07-17T09:00:00Z",
        sessions = List(
          durable(stage = Some("Plan"), lastActiveAt = "2026-07-17T09:05:00Z")
        )
      ),
      crashed = false
    )
    val attempt2 = RecordedAttempt(
      manifest(
        startedAt = "2026-07-18T09:00:00Z",
        sessions = List(
          durable(stage = Some("Task"), lastActiveAt = "2026-07-18T09:05:00Z")
        )
      ),
      crashed = false
    )
    val rows =
      SessionPicker.sessionRows(List(attempt2, attempt1), expanded = true)
    assertEquals(
      rows.map(_.label),
      List(
        "★ main — latest (stage: Task) [claude]",
        "main — stage Plan [claude] (earlier occurrence)"
      )
    )

  test(
    "sessionRows (expanded): ephemeral rows are labeled with agent, role, stage and an (ephemeral) marker"
  ):
    val run = RecordedAttempt(
      manifest(sessions =
        List(
          ephemeral(
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
        "code-structure (reviewer) — stage Task: add auth [claude] (ephemeral)"
      )
    )

  test(
    "sessionRows omits the earlier-occurrences expander when there's only one occurrence"
  ):
    val run = RecordedAttempt(
      manifest(sessions = List(durable())),
      crashed = false
    )
    assertEquals(
      SessionPicker.sessionRows(List(run), expanded = false).map(_.label),
      List("★ main — latest (no stage yet) [claude]")
    )

  test("sessionRows singularises a count of 1 in the expander label"):
    val run = RecordedAttempt(
      manifest(sessions = List(durable(), ephemeral())),
      crashed = false
    )
    assertEquals(
      SessionPicker.sessionRows(List(run), expanded = false).map(_.label),
      List(
        "★ main — latest (no stage yet) [claude]",
        "… show 1 ephemeral session (reviews, plan steps)"
      )
    )

  test(
    "sessionRows keeps two sessions sharing a name apart by their minting stage"
  ):
    val run = RecordedAttempt(
      manifest(sessions =
        List(
          durable(
            agent = "coder",
            sessionName = "implementer",
            sessionStage = "Task: parse the input#0",
            stage = Some("Task: parse the input"),
            lastActiveAt = "2026-07-18T09:00:00Z"
          ),
          durable(
            agent = "coder",
            sessionName = "implementer",
            sessionStage = "Task: wire the parser#0",
            stage = Some("Task: wire the parser"),
            lastActiveAt = "2026-07-18T09:05:00Z"
          )
        )
      ),
      crashed = false
    )
    assertEquals(
      SessionPicker.sessionRows(List(run), expanded = false).map(_.label),
      List(
        "★ implementer — latest (stage: Task: wire the parser) [claude]",
        "★ implementer — latest (stage: Task: parse the input) [claude]"
      )
    )

  test(
    "sessionRows groups durable lineages by (agent, sessionName), not agent alone"
  ):
    val run = RecordedAttempt(
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

  test("sessionRows keeps two runs in one directory apart by their branch"):
    val attempts = List(
      "feat-a" -> "2026-07-18T10:00:00Z",
      "feat-b" -> "2026-07-18T09:00:00Z"
    ).map: (b, at) =>
      RecordedAttempt(
        manifest(branch = Some(b), sessions = List(durable(lastActiveAt = at))),
        crashed = false
      )
    assertEquals(
      SessionPicker.sessionRows(attempts, expanded = false).map(_.label),
      List(
        "★ main — latest (no stage yet) [claude] on feat-a",
        "★ main — latest (no stage yet) [claude] on feat-b"
      )
    )

  test("sessionRows groups resumed attempts on one branch into one lineage"):
    val attempts = List("2026-07-18T10:00:00Z", "2026-07-18T09:00:00Z").map:
      at =>
        RecordedAttempt(
          manifest(
            branch = Some("feat-a"),
            sessions = List(durable(lastActiveAt = at))
          ),
          crashed = false
        )
    assertEquals(
      SessionPicker.sessionRows(attempts, expanded = false).map(_.label),
      List(
        "★ main — latest (no stage yet) [claude] on feat-a",
        "… show 1 earlier occurrence"
      )
    )

  test(
    "sessionRows (expanded): earlier and ephemeral rows carry the attempt's branch"
  ):
    val attempts = List(
      "2026-07-18T10:00:00Z" -> List(
        durable(lastActiveAt = "2026-07-18T10:00:00Z")
      ),
      "2026-07-18T09:00:00Z" -> List(
        durable(lastActiveAt = "2026-07-18T09:00:00Z"),
        ephemeral(agent = "security", lastActiveAt = "2026-07-18T09:10:00Z")
      )
    ).map: (startedAt, sessions) =>
      RecordedAttempt(
        manifest(
          startedAt = startedAt,
          branch = Some("feat-a"),
          sessions = sessions
        ),
        crashed = false
      )
    assertEquals(
      SessionPicker.sessionRows(attempts, expanded = true).map(_.label).tail,
      List(
        "main [claude] (earlier occurrence) on feat-a",
        "security [claude] (ephemeral) on feat-a"
      )
    )

  test("sessionRows suffixes a crashed attempt's rows with `(crashed)`"):
    val run =
      RecordedAttempt(manifest(sessions = List(durable())), crashed = true)
    assertEquals(
      SessionPicker.sessionRows(List(run), expanded = false).map(_.label),
      List("★ main — latest (no stage yet) [claude] (crashed)")
    )

  test("sessionRows disables a wireId-less session, naming its harness"):
    val run = RecordedAttempt(
      manifest(sessions =
        List(durable(harness = BackendTag.Pi, wireId = None))
      ),
      crashed = false
    )
    assertEquals(
      SessionPicker
        .sessionRows(List(run), expanded = false)
        .map(_.disabledReason),
      List(Some("Pi session has no resumable id"))
    )

  test("sessionRows enables a claude session with a wireId"):
    val run =
      RecordedAttempt(manifest(sessions = List(durable())), crashed = false)
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
      origin = Origin.BuiltIn,
      path = os.root / s"$name",
      shadows = Nil
    )

  /** [[flow]] with an explicit origin/path — for tests that need a non-built-in
    * origin, or a real on-disk path since the hand-mode paths (edit-in-place,
    * customize-into-tier, fork-copy) actually read/copy the file.
    */
  private def flowAt(
      name: String,
      origin: Origin,
      path: os.Path
  ): DiscoveredFlow =
    DiscoveredFlow(
      name = name,
      description = None,
      origin = origin,
      path = path,
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
    val ui = new RecordingSelectUi[DiscoveredFlow](UiOutcome.Cancelled)
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
    val ui = new RecordingSelectUi[DiscoveredFlow](UiOutcome.Cancelled)
    val _ = Main.pickFlow(ui, "View which flow?", threeFlows)
    assertEquals(
      ui.recordedChoices.head.map(_.value.name),
      List("alpha.sc", "implement.sc", "zeta.sc")
    )

  // --- promptRunTarget (where the run's work goes, asked as one choice) ---

  private val newBranch = RunTarget.NewBranch(Uncommitted.Stash)

  test("promptRunTarget: the three destinations are offered, new branch first"):
    val ui = RecordingSelectUi(UiOutcome.Selected(newBranch))
    assertEquals(Main.promptRunTarget(ui), Some(newBranch))
    assertEquals(
      ui.recordedChoices.head.map(_.value),
      List(
        newBranch,
        RunTarget.CurrentBranch(Uncommitted.Stash),
        RunTarget.Worktree
      )
    )

  test(
    "promptRunTarget: a new branch leads the rows and is marked preselected"
  ):
    val ui = RecordingSelectUi(UiOutcome.Selected(newBranch))
    assertEquals(Main.promptRunTarget(ui), Some(newBranch))
    assertEquals(ui.recordedPreselect, Some(Some(newBranch)))

  test("promptRunTarget: cancelling aborts the run"):
    assertEquals(
      Main.promptRunTarget(RecordingSelectUi[RunTarget](UiOutcome.Cancelled)),
      None
    )

  // --- runFlow (the interactive launch path) ---

  /** Runs [[Main.runFlow]] picking a flow, typing a task, then picking `target`
    * and answering the branch prompt from `branchAnswers`; returns the UI and
    * the args that reached the launcher.
    */
  private def runFlowWith(
      target: RunTarget,
      branchAnswers: List[UiOutcome[String]]
  ): (FlowScriptedUi, Option[OrcaArgs]) =
    val workDir = TempDirs.dir()
    val flowPath = workDir / ".orca" / "flows" / "run-flow.sc"
    os.write(flowPath, "// x\n", createFolders = true)
    val flow = DiscoveredFlow(
      name = "run-flow.sc",
      description = None,
      origin = Origin.Project,
      path = flowPath,
      shadows = Nil
    )
    val ui = FlowScriptedUi(
      selectScript = List(UiOutcome.Selected(flow), UiOutcome.Selected(target)),
      inputMultilineScript = List(UiOutcome.Selected("do the thing")),
      inputScript = branchAnswers
    )
    var recorded: Option[OrcaArgs] = None
    withDumbTerminal: terminal =>
      Main.runFlow(
        ui,
        terminal,
        workDir,
        runAction = (_, opts, _, _) =>
          recorded = Some(opts.args)
          LaunchResult.Ok
      )
    (ui, recorded)

  test("runFlow: a typed branch name reaches the launcher's args"):
    val (_, args) =
      runFlowWith(RunTarget.Worktree, List(UiOutcome.Selected("feature/x")))
    assertEquals(args.map(_.target), Some(RunTarget.Worktree))
    assertEquals(args.flatMap(_.branch).map(_.value), Some("feature/x"))

  test("runFlow: an invalid branch name is re-asked and the next one used"):
    val (ui, args) = runFlowWith(
      RunTarget.NewBranch(Uncommitted.Stash),
      List(UiOutcome.Selected("bad name"), UiOutcome.Selected("good-name"))
    )
    assertEquals(ui.inputCount, 2)
    assertEquals(args.flatMap(_.branch).map(_.value), Some("good-name"))

  test("runFlow: Enter at the branch prompt lets the flow derive the name"):
    val target = RunTarget.NewBranch(Uncommitted.Stash)
    val (_, args) = runFlowWith(target, List(UiOutcome.Selected("")))
    assertEquals(
      args,
      Some(
        OrcaArgs(
          userPrompt = "do the thing",
          verbose = false,
          target = target,
          branch = None
        )
      )
    )

  test("runFlow: cancelling the branch prompt aborts the run"):
    val (ui, args) = runFlowWith(RunTarget.Worktree, List(UiOutcome.Cancelled))
    assertEquals(ui.inputCount, 1)
    assertEquals(args, None)

  test("runFlow: the current-branch target asks no branch name"):
    val (ui, args) =
      runFlowWith(RunTarget.CurrentBranch(Uncommitted.Stash), Nil)
    assertEquals(ui.inputCount, 0)
    assertEquals(args.map(_.branch), Some(None))

  // --- editFlow / createNewFlow / createForkFlow (ADR 0021 §6/§9 amendment:
  // hand-vs-agent mode) ---
  //
  // The agent paths' launch itself (AuthorAction) is exercised separately in
  // AuthorActionTest with an injected launcher; here these only cover the
  // menu-side prompting, up to the point where a real prompt is cancelled —
  // same convention the pre-existing create/fork cancel tests already used.
  // The hand paths never reach AuthorAction at all, so their full write +
  // editor-spawn behavior IS covered end to end, via the same injected
  // `spawnEditor` seam `editSettings` uses.

  private def withDumbTerminal(body: Terminal => Unit): Unit =
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try body(terminal)
    finally terminal.close()

  // --- editFlow ---

  test("editFlow: cancelling the flow prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript = List(UiOutcome.Cancelled))
      Main.editFlow(ui, terminal)
      assertEquals(ui.selectCount, 1)

  test("editFlow: cancelling the mode prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(flow("implement.sc")), UiOutcome.Cancelled)
      )
      Main.editFlow(ui, terminal)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputMultilineCount, 0)

  test(
    "editFlow: Hand + a non-built-in flow opens the editor on its own path directly"
  ):
    withDumbTerminal: terminal =>
      val source =
        flowAt("my-flow.sc", Origin.Project, TempDirs.dir() / "my-flow.sc")
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(source), UiOutcome.Selected(ChangeMode.Hand))
      )
      var spawned: Option[os.Path] = None
      Main.editFlow(
        ui,
        terminal,
        spawnEditor = (_, path) => { spawned = Some(path); 0 }
      )
      assertEquals(spawned, Some(source.path))

  test(
    "editFlow: Hand + a built-in flow customizes into the picked tier, then opens the copy"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      val sourcePath = TempDirs.dir() / "my-flow.sc"
      os.write(sourcePath, "// x\n")
      val source = flowAt("my-flow.sc", Origin.BuiltIn, sourcePath)
      val ui = FlowScriptedUi(selectScript =
        List(
          UiOutcome.Selected(source),
          UiOutcome.Selected(ChangeMode.Hand),
          UiOutcome.Selected(CreateTier.Project)
        )
      )
      var spawned: Option[os.Path] = None
      Main.editFlow(
        ui,
        terminal,
        workDir = workDir,
        spawnEditor = (_, path) => { spawned = Some(path); 0 }
      )
      val expected = workDir / ".orca" / "flows" / "my-flow.sc"
      assertEquals(spawned, Some(expected))
      assertEquals(os.read(expected), "// x\n")

  test(
    "editFlow: Hand + built-in, cancelling the customize-tier prompt opens no editor"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(
          UiOutcome.Selected(flow("implement.sc")),
          UiOutcome.Selected(ChangeMode.Hand),
          UiOutcome.Cancelled
        )
      )
      var spawnCount = 0
      Main.editFlow(
        ui,
        terminal,
        spawnEditor = (_, _) => { spawnCount += 1; 0 }
      )
      assertEquals(spawnCount, 0)

  test(
    "editFlow: Agent mode — cancelling the changes prompt asks nothing else"
  ):
    withDumbTerminal: terminal =>
      val source =
        flowAt("my-flow.sc", Origin.Project, os.root / "my-flow.sc")
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(source),
          UiOutcome.Selected(ChangeMode.Agent)
        ),
        inputMultilineScript = List(UiOutcome.Cancelled)
      )
      Main.editFlow(ui, terminal)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputMultilineCount, 1)

  test(
    "editFlow: Agent mode with a built-in source — cancelling the customize-tier prompt asks nothing else"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(
          UiOutcome.Selected(flow("implement.sc")),
          UiOutcome.Selected(ChangeMode.Agent),
          UiOutcome.Cancelled
        )
      )
      Main.editFlow(ui, terminal)
      assertEquals(ui.selectCount, 3)
      assertEquals(ui.inputMultilineCount, 0)

  // --- createNewFlow ---

  test("createNewFlow: cancelling the mode prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript = List(UiOutcome.Cancelled))
      Main.createNewFlow(ui, terminal)
      assertEquals(ui.selectCount, 1)
      assertEquals(ui.inputMultilineCount, 0)
      assertEquals(ui.inputCount, 0)

  test("createNewFlow (Agent): cancelling the tier prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(ChangeMode.Agent), UiOutcome.Cancelled)
      )
      Main.createNewFlow(ui, terminal)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputMultilineCount, 0)

  test(
    "createNewFlow (Agent): cancelling the goal prompt stops before anything is written"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(ChangeMode.Agent),
          UiOutcome.Selected(CreateTier.Project)
        ),
        inputMultilineScript = List(UiOutcome.Cancelled)
      )
      Main.createNewFlow(ui, terminal)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputMultilineCount, 1)
      assertEquals(ui.inputCount, 0)

  test(
    "createNewFlow (Hand): cancelling the tier prompt asks for no filename"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(ChangeMode.Hand), UiOutcome.Cancelled)
      )
      Main.createNewFlow(ui, terminal)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputCount, 0)

  test(
    "createNewFlow (Hand): cancelling the filename prompt writes nothing and opens no editor"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(ChangeMode.Hand),
          UiOutcome.Selected(CreateTier.Project)
        ),
        inputScript = List(UiOutcome.Cancelled)
      )
      var spawnCount = 0
      Main.createNewFlow(
        ui,
        terminal,
        workDir = TempDirs.dir(),
        spawnEditor = (_, _) => { spawnCount += 1; 0 }
      )
      assertEquals(spawnCount, 0)

  test(
    "createNewFlow (Hand): writes the skeleton flow and opens it in the editor"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(ChangeMode.Hand),
          UiOutcome.Selected(CreateTier.Project)
        ),
        inputScript = List(UiOutcome.Selected("my-new-flow.sc"))
      )
      var spawned: Option[os.Path] = None
      Main.createNewFlow(
        ui,
        terminal,
        workDir = workDir,
        spawnEditor = (_, path) => { spawned = Some(path); 0 }
      )
      val expected = workDir / ".orca" / "flows" / "my-new-flow.sc"
      assertEquals(spawned, Some(expected))
      assertEquals(
        os.read(expected),
        orca.shell.create.FlowAuthoring.skeletonFlow(ShellVersion.value)
      )

  test(
    "createNewFlow (Hand): a filename collision re-prompts instead of aborting"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      os.write(
        workDir / ".orca" / "flows" / "taken.sc",
        "// existing\n",
        createFolders = true
      )
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(ChangeMode.Hand),
          UiOutcome.Selected(CreateTier.Project)
        ),
        inputScript =
          List(UiOutcome.Selected("taken.sc"), UiOutcome.Selected("free.sc"))
      )
      var spawned: Option[os.Path] = None
      Main.createNewFlow(
        ui,
        terminal,
        workDir = workDir,
        spawnEditor = (_, path) => { spawned = Some(path); 0 }
      )
      assertEquals(ui.inputCount, 2)
      assertEquals(spawned, Some(workDir / ".orca" / "flows" / "free.sc"))

  // --- createForkFlow ---

  test("createForkFlow: cancelling the source prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript = List(UiOutcome.Cancelled))
      Main.createForkFlow(ui, terminal)
      assertEquals(ui.selectCount, 1)
      assertEquals(ui.inputMultilineCount, 0)
      assertEquals(ui.inputCount, 0)

  test(
    "createForkFlow: cancelling the tier prompt stops before the mode prompt"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(flow("implement.sc")), UiOutcome.Cancelled)
      )
      Main.createForkFlow(ui, terminal)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputMultilineCount, 0)

  test(
    "createForkFlow: cancelling the mode prompt is the last stop before hand/agent would proceed"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(
          UiOutcome.Selected(flow("implement.sc")),
          UiOutcome.Selected(CreateTier.Project),
          UiOutcome.Cancelled
        )
      )
      Main.createForkFlow(ui, terminal)
      assertEquals(ui.selectCount, 3)
      assertEquals(ui.inputMultilineCount, 0)

  test(
    "createForkFlow (Agent): cancelling the changes prompt stops before authoring would launch"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(flow("implement.sc")),
          UiOutcome.Selected(CreateTier.Project),
          UiOutcome.Selected(ChangeMode.Agent)
        ),
        inputMultilineScript = List(UiOutcome.Cancelled)
      )
      Main.createForkFlow(ui, terminal)
      assertEquals(ui.selectCount, 3)
      assertEquals(ui.inputMultilineCount, 1)

  test(
    "createForkFlow (Hand): copies the source to the auto target and opens it in the editor"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      val sourcePath = TempDirs.dir() / "implement.sc"
      os.write(sourcePath, "// source content\n")
      val source = flowAt("implement.sc", Origin.Global, sourcePath)
      val ui = FlowScriptedUi(selectScript =
        List(
          UiOutcome.Selected(source),
          UiOutcome.Selected(CreateTier.Project),
          UiOutcome.Selected(ChangeMode.Hand)
        )
      )
      var spawned: Option[os.Path] = None
      Main.createForkFlow(
        ui,
        terminal,
        workDir = workDir,
        spawnEditor = (_, path) => { spawned = Some(path); 0 }
      )
      val expected = workDir / ".orca" / "flows" / "implement-fork.sc"
      assertEquals(spawned, Some(expected))
      assertEquals(os.read(expected), "// source content\n")

  // --- resumeInterruptedRun ---
  //
  // `runAction` is injected (AuthorAction-style seam) so these never spawn a
  // real `scala-cli` subprocess; the recorded call's flow+task is what the
  // resume offer promises: byte-identical to what's stored on `InterruptedRun`.

  test(
    "resumeInterruptedRun: resolves the recorded flow name and launches with the recorded task, verbatim"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      os.write(
        workDir / ".orca" / "flows" / "resume-flow.sc",
        "// x\n",
        createFolders = true
      )
      val run = InterruptedRun(
        flowName = "resume-flow.sc",
        userPrompt = "fix the flaky test\nwith detail",
        branch = "feat/x",
        dir = workDir
      )
      var recorded: Option[(String, String)] = None
      Main.resumeInterruptedRun(
        FlowScriptedUi(),
        terminal,
        run,
        runAction = (flow, opts, _, _) =>
          recorded = Some(flow.name -> opts.args.userPrompt)
          LaunchResult.Ok
      )
      assertEquals(
        recorded,
        Some("resume-flow.sc" -> "fix the flaky test\nwith detail")
      )

  test(
    "resumeInterruptedRun: the run happens in the directory its log was found in"
  ):
    withDumbTerminal: terminal =>
      // A log found in an orca worktree resumes THERE, with no --worktree flag:
      // the flag would re-derive a path, this runs where the log actually is.
      val worktree = TempDirs.dir()
      os.write(
        worktree / ".orca" / "flows" / "resume-flow.sc",
        "// x\n",
        createFolders = true
      )
      val run = InterruptedRun(
        flowName = "resume-flow.sc",
        userPrompt = "fix the flaky test",
        branch = "feat/x",
        dir = worktree
      )
      var recorded: Option[(os.Path, RunTarget)] = None
      Main.resumeInterruptedRun(
        FlowScriptedUi(),
        terminal,
        run,
        runAction = (_, opts, dir, _) =>
          recorded = Some(dir -> opts.args.target)
          LaunchResult.Ok
      )
      assertEquals(
        recorded,
        Some(worktree -> RunTarget.NewBranch(Uncommitted.Stash))
      )

  test(
    "resumeInterruptedRun: an unresolvable flow name reports an error and never launches"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      val run = InterruptedRun(
        flowName = "no-such-flow.sc",
        userPrompt = "x",
        branch = "feat/x",
        dir = workDir
      )
      var launched = false
      val out = captured(
        Main.resumeInterruptedRun(
          FlowScriptedUi(),
          terminal,
          run,
          runAction = (_, _, _, _) => { launched = true; LaunchResult.Ok }
        )
      )
      assert(
        !launched,
        "runAction must not run when the flow can't be resolved"
      )
      assert(out.contains("no-such-flow.sc"), out)

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
      assertEquals(os.read(expected), SettingsEditAction.ProjectTemplate)

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
    val content = SettingsFile.Header + "\n" +
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
