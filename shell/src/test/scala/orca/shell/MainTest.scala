package orca.shell

import orca.StackSettings
import orca.agents.BackendTag
import orca.runner.{ManifestSession, RunManifest}
import orca.settings.SettingsFile
import orca.shell.actions.StackAction
import orca.shell.create.CreateTier
import orca.shell.flows.{DiscoveredFlow, FlowOrigin}
import orca.shell.sessions.{RecordedRun, SessionPicker, SessionSelection}
import orca.shell.ui.{Choice, ShellUi, UiOutcome}
import orca.shell.wizard.ModelCatalog
import orca.testkit.TempDirs

/** Answers a single fixed `confirm` outcome; every other prompt is unsupported
  * — [[Main.rediscoverStack]] only ever calls `confirm`.
  */
private class ConfirmOnlyUi(outcome: UiOutcome[Boolean]) extends ShellUi:
  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    throw new UnsupportedOperationException("rediscoverStack doesn't select")
  def confirm(question: String, default: Boolean): UiOutcome[Boolean] = outcome
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

/** Answers a queue of `input` outcomes in order; every other prompt is
  * unsupported — [[Main.promptFlowTarget]] only ever calls `input`.
  */
private class InputQueueUi(inputs: List[UiOutcome[String]]) extends ShellUi:
  private var pending = inputs
  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    throw new UnsupportedOperationException("promptFlowTarget doesn't select")
  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    throw new UnsupportedOperationException(
      "promptFlowTarget doesn't confirm"
    )
  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    val outcome = pending.head
    pending = pending.tail
    outcome
  def inputMultiline(prompt: String): UiOutcome[String] =
    throw new UnsupportedOperationException(
      "promptFlowTarget doesn't input-multiline"
    )

/** Records every `select` call's title and preselect, and replays queued
  * outcomes for `select`/`input`/`confirm` — exercises
  * [[Main.selectHarness]]/[[Main.selectAuthoringModel]]/
  * [[Main.selectHarnessModelAndYolo]] the same way WizardTest's own
  * `ScriptedUi` exercises the wizard's harness+model prompts. `inputMultiline`
  * is unsupported: none of those call it.
  */
private class AuthoringScriptedUi(
    selectScript: List[UiOutcome[Any]] = Nil,
    inputScript: List[UiOutcome[String]] = Nil,
    confirmScript: List[UiOutcome[Boolean]] = Nil
) extends ShellUi:
  private var pendingSelect = selectScript
  private var pendingInput = inputScript
  private var pendingConfirm = confirmScript
  private var titles: List[String] = Nil
  private var preselects: List[Option[Any]] = Nil

  def recordedTitles: List[String] = titles
  def recordedPreselects: List[Option[Any]] = preselects

  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    titles = titles :+ title
    preselects = preselects :+ preselect.asInstanceOf[Option[Any]]
    val outcome = pendingSelect.head
    pendingSelect = pendingSelect.tail
    outcome.asInstanceOf[UiOutcome[A]]

  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    val outcome = pendingConfirm.head
    pendingConfirm = pendingConfirm.tail
    outcome

  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    val outcome = pendingInput.head
    pendingInput = pendingInput.tail
    outcome

  def inputMultiline(prompt: String): UiOutcome[String] =
    throw new UnsupportedOperationException(
      "authoring prompts don't use inputMultiline"
    )

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

  test("harnessLabel suffixes a detected harness with the found marker"):
    assertEquals(
      Main.harnessLabel(BackendTag.ClaudeCode, _ => true),
      "claude — ✓ found"
    )

  test("harnessLabel suffixes an undetected harness with not-found-on-PATH"):
    assertEquals(
      Main.harnessLabel(BackendTag.ClaudeCode, _ => false),
      "claude — not found on PATH"
    )

  // --- authoring: harness + model prompts ---

  private def emptySettingsPath: os.Path =
    TempDirs.dir() / "settings.properties"

  test("selectHarness: create and fork ask differently-worded questions"):
    val settings = emptySettingsPath
    val createUi =
      AuthoringScriptedUi(List(UiOutcome.Selected(BackendTag.ClaudeCode)))
    val _ = Main.selectHarness(createUi, Main.AuthoringAction.Create, settings)
    assertEquals(
      createUi.recordedTitles,
      List("Coding agent to write the flow script:")
    )

    val forkUi =
      AuthoringScriptedUi(List(UiOutcome.Selected(BackendTag.ClaudeCode)))
    val _ = Main.selectHarness(forkUi, Main.AuthoringAction.Fork, settings)
    assertEquals(
      forkUi.recordedTitles,
      List("Coding agent to edit the forked flow:")
    )

  test(
    "selectAuthoringModel: preselects/orders the configured coding pin first when its harness matches"
  ):
    val settings = TempDirs.dir() / "settings.properties"
    os.write(settings, "codingAgent = claude:sonnet\n")
    val ui = AuthoringScriptedUi(
      List(UiOutcome.Selected(ModelCatalog.ModelPick.Curated("sonnet")))
    )
    val result = Main.selectAuthoringModel(
      ui,
      Main.AuthoringAction.Create,
      BackendTag.ClaudeCode,
      settings
    )
    assertEquals(result, Some(Some("sonnet")))
    assertEquals(
      ui.recordedPreselects,
      List(Some(ModelCatalog.ModelPick.Curated("sonnet")))
    )

  test(
    "selectAuthoringModel: falls back to the coding-role default when the configured pin's harness differs"
  ):
    val settings = TempDirs.dir() / "settings.properties"
    os.write(settings, "codingAgent = gemini:some-model\n")
    val ui = AuthoringScriptedUi(
      List(UiOutcome.Selected(ModelCatalog.ModelPick.Curated("opus")))
    )
    val result = Main.selectAuthoringModel(
      ui,
      Main.AuthoringAction.Create,
      BackendTag.ClaudeCode,
      settings
    )
    assertEquals(result, Some(Some("opus")))
    assertEquals(
      ui.recordedPreselects,
      List(Some(ModelCatalog.ModelPick.Curated("opus")))
    )

  test(
    "selectHarnessModelAndYolo: a curated model pick is threaded through alongside the harness and yolo choice"
  ):
    val ui = AuthoringScriptedUi(
      selectScript = List(
        UiOutcome.Selected(BackendTag.ClaudeCode),
        UiOutcome.Selected(ModelCatalog.ModelPick.Curated("opus"))
      ),
      confirmScript = List(UiOutcome.Selected(true))
    )
    assertEquals(
      Main.selectHarnessModelAndYolo(
        ui,
        Main.AuthoringAction.Create,
        emptySettingsPath
      ),
      Some((BackendTag.ClaudeCode, Some("opus"), true))
    )

  test(
    "selectHarnessModelAndYolo: cancelling the model prompt aborts without ever asking about yolo"
  ):
    val ui = AuthoringScriptedUi(
      selectScript =
        List(UiOutcome.Selected(BackendTag.ClaudeCode), UiOutcome.Cancelled)
      // confirmScript is empty on purpose: if yolo were asked anyway, `.head`
      // on the empty queue would throw, failing this test loudly.
    )
    assertEquals(
      Main.selectHarnessModelAndYolo(
        ui,
        Main.AuthoringAction.Create,
        emptySettingsPath
      ),
      None
    )

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

  // --- promptFlowTarget (F1: a `/` in the filename must re-prompt, not crash) ---

  test(
    "promptFlowTarget: a filename with a path separator re-prompts instead of crashing"
  ):
    val dir = TempDirs.dir()
    val ui = InputQueueUi(
      List(UiOutcome.Selected("sub/x"), UiOutcome.Selected("valid-name"))
    )
    val result =
      Main.promptFlowTarget(ui, CreateTier.Project, dir, dir / "global", None)
    assertEquals(
      result.map(_.flowPath),
      Some(dir / ".orca" / "flows" / "valid-name.sc")
    )

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
