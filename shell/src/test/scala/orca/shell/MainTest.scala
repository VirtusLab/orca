package orca.shell

import orca.StackSettings
import orca.agents.BackendTag
import orca.runner.{ManifestSession, RunManifest}
import orca.settings.SettingsFile
import orca.shell.sessions.ReadRun
import orca.shell.ui.{Choice, ShellUi, UiOutcome}
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
      rows: List[orca.shell.ui.Choice[Main.PickerRow]]
  ): List[Main.SessionSelection] =
    rows.collect {
      case orca.shell.ui.Choice(Main.PickerRow.Resume(s), _, _, _) =>
        s
    }

  // -- Realistic mixed fixture (mirrors the user's observed session mix,
  // research 08 items 7+8): a "main" coder lineage resumed/re-run across three
  // separate flow runs (so three occurrences, newest last-active wins), a
  // Plan-stage one-shot, and three reviewer one-shots.
  private def mixedRuns(): List[ReadRun] =
    val run1 = ReadRun(
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
    val run2 = ReadRun(
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
    val run3 = ReadRun(
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
    val rows = Main.sessionRows(mixedRuns(), expanded = false)
    val resumes = resumeSelections(rows)
    assertEquals(resumes.map(_.session.stage), List(Some("Task: fix bug")))

  test("sessionRows (collapsed): starred row is labeled with the session name"):
    val rows = Main.sessionRows(mixedRuns(), expanded = false)
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
    val rows = Main.sessionRows(mixedRuns(), expanded = false)
    assertEquals(rows.size, 3)
    assertEquals(rows(1).value, Main.PickerRow.ShowMore)
    assertEquals(rows(2).value, Main.PickerRow.ShowMore)

  test(
    "sessionRows (expanded): reveals earlier occurrences and one-shots, no expander rows"
  ):
    val rows = Main.sessionRows(mixedRuns(), expanded = true)
    assert(!rows.exists(_.value == Main.PickerRow.ShowMore))
    // 1 starred + 2 earlier occurrences + 4 one-shots
    assertEquals(rows.size, 7)

  test(
    "sessionRows (expanded): earlier occurrences and one-shots are each sorted newest-first"
  ):
    val rows = Main.sessionRows(mixedRuns(), expanded = true)
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
    val run1 = ReadRun(
      manifest(
        startedAt = "2026-07-17T09:00:00Z",
        sessions = List(
          durable(stage = Some("Plan"), lastActiveAt = "2026-07-17T09:05:00Z")
        )
      ),
      crashed = false
    )
    val run2 = ReadRun(
      manifest(
        startedAt = "2026-07-18T09:00:00Z",
        sessions = List(
          durable(stage = Some("Task"), lastActiveAt = "2026-07-18T09:05:00Z")
        )
      ),
      crashed = false
    )
    val rows = Main.sessionRows(List(run2, run1), expanded = true)
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
    val run = ReadRun(
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
      Main.sessionRows(List(run), expanded = true).map(_.label),
      List(
        "code-structure (reviewer) — stage Task: add auth [claude] (one-shot)"
      )
    )

  test(
    "sessionRows omits the earlier-occurrences expander when there's only one occurrence"
  ):
    val run = ReadRun(
      manifest(sessions = List(durable())),
      crashed = false
    )
    assertEquals(
      Main.sessionRows(List(run), expanded = false).map(_.label),
      List("★ main — latest (no stage yet) [claude]")
    )

  test("sessionRows singularises a count of 1 in the expander label"):
    val run = ReadRun(
      manifest(sessions = List(durable(), oneShot())),
      crashed = false
    )
    assertEquals(
      Main.sessionRows(List(run), expanded = false).map(_.label),
      List(
        "★ main — latest (no stage yet) [claude]",
        "… show 1 one-shot session (reviews, plan steps)"
      )
    )

  test(
    "sessionRows groups durable lineages by (agent, sessionName), not agent alone"
  ):
    val run = ReadRun(
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
    val rows = Main.sessionRows(List(run), expanded = false)
    assertEquals(
      rows.map(_.label),
      List(
        "★ helper — latest (no stage yet) [claude]",
        "★ main — latest (no stage yet) [claude]"
      )
    )

  test("sessionRows suffixes a crashed run's rows with `(crashed)`"):
    val run = ReadRun(manifest(sessions = List(durable())), crashed = true)
    assertEquals(
      Main.sessionRows(List(run), expanded = false).map(_.label),
      List("★ main — latest (no stage yet) [claude] (crashed)")
    )

  test(
    "sessionRows falls back to the raw harness string for an unrecognised one"
  ):
    val run = ReadRun(
      manifest(sessions = List(durable(harness = "SomeFutureHarness"))),
      crashed = false
    )
    assertEquals(
      Main.sessionRows(List(run), expanded = false).map(_.label),
      List("★ main — latest (no stage yet) [SomeFutureHarness]")
    )

  test("sessionRows disables a wireId-less session with its stored reason"):
    val reason = "pi sessions are deleted when the run's temp dir is reclaimed"
    val run = ReadRun(
      manifest(sessions =
        List(durable(harness = "Pi", wireId = None, reason = Some(reason)))
      ),
      crashed = false
    )
    assertEquals(
      Main.sessionRows(List(run), expanded = false).map(_.disabledReason),
      List(Some(reason))
    )

  test("sessionRows disables an unrecognised harness"):
    val run = ReadRun(
      manifest(sessions = List(durable(harness = "SomeFutureHarness"))),
      crashed = false
    )
    assert(
      Main
        .sessionRows(List(run), expanded = false)
        .head
        .disabledReason
        .isDefined
    )

  test("sessionRows enables a claude session with a wireId"):
    val run = ReadRun(manifest(sessions = List(durable())), crashed = false)
    assertEquals(
      Main.sessionRows(List(run), expanded = false).map(_.disabledReason),
      List(None)
    )

  test(
    "sessionRows enables a gemini session with a wireId, deferring the real index lookup to selection"
  ):
    val run = ReadRun(
      manifest(sessions =
        List(durable(harness = "Gemini", wireId = Some("uuid")))
      ),
      crashed = false
    )
    assertEquals(
      Main.sessionRows(List(run), expanded = false).map(_.disabledReason),
      List(None)
    )

  test(
    "sessionRows is a silent no-op shape on an empty run list (no rows, no crash)"
  ):
    assertEquals(Main.sessionRows(Nil, expanded = false), Nil)
    assertEquals(Main.sessionRows(Nil, expanded = true), Nil)

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

  test("validatedWorkDir accepts a path that's still a directory"):
    val dir = TempDirs.dir()
    assertEquals(Main.validatedWorkDir(dir.toString), Right(dir))

  test(
    "validatedWorkDir rejects a relative/malformed path (os.Path's IllegalArgumentException)"
  ):
    assertEquals(
      Main.validatedWorkDir("not-an-absolute-path"),
      Left(
        "the recorded working directory not-an-absolute-path no longer exists"
      )
    )

  test(
    "validatedWorkDir rejects a well-formed but deleted directory (os.proc's cwd IOException, guarded before it happens)"
  ):
    val dir = TempDirs.dir()
    os.remove.all(dir)
    assertEquals(
      Main.validatedWorkDir(dir.toString),
      Left(s"the recorded working directory $dir no longer exists")
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
      Main.renderStackSettings(
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
      Main
        .renderStackSettings(StackSettings.empty)
        .contains("no live commands")
    )
