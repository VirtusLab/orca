package orca.shell.sessions

import orca.StagePath
import orca.agents.BackendTag
import orca.shell.sessions.ManifestFixtures.{durable, ephemeral, manifest}

class SessionPickerTest extends munit.FunSuite:

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
  private def mixedAttempts(): List[orca.shell.sessions.RecordedAttempt] =
    val attempt1 = ManifestFixtures.recorded(
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
    val attempt2 = ManifestFixtures.recorded(
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
    val attempt3 = ManifestFixtures.recorded(
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
    val rows = SessionPicker.sessionRows(
      SessionIndex.of(mixedAttempts()),
      expanded = false
    )
    val resumes = resumeSelections(rows)
    assertEquals(resumes.map(_.session.stage), List(Some("Task: fix bug")))

  test(
    "sessionRows (collapsed): render shape is the starred row plus both expander labels"
  ):
    val rows = SessionPicker.sessionRows(
      SessionIndex.of(mixedAttempts()),
      expanded = false
    )
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
    val rows = SessionPicker.sessionRows(
      SessionIndex.of(mixedAttempts()),
      expanded = false
    )
    assertEquals(rows.size, 3)
    assertEquals(rows(1).value, SessionPicker.PickerRow.ShowMore)
    assertEquals(rows(2).value, SessionPicker.PickerRow.ShowMore)

  test(
    "sessionRows (expanded): reveals earlier occurrences and ephemeral sessions, no expander rows"
  ):
    val rows = SessionPicker.sessionRows(
      SessionIndex.of(mixedAttempts()),
      expanded = true
    )
    assert(!rows.exists(_.value == SessionPicker.PickerRow.ShowMore))
    // 1 starred + 2 earlier occurrences + 4 ephemeral sessions
    assertEquals(rows.size, 7)

  test(
    "sessionRows (expanded): earlier occurrences and ephemeral sessions are each sorted newest-first"
  ):
    val rows = SessionPicker.sessionRows(
      SessionIndex.of(mixedAttempts()),
      expanded = true
    )
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
    val attempt1 = ManifestFixtures.recorded(
      manifest(
        startedAt = "2026-07-17T09:00:00Z",
        sessions = List(
          durable(stage = Some("Plan"), lastActiveAt = "2026-07-17T09:05:00Z")
        )
      ),
      crashed = false
    )
    val attempt2 = ManifestFixtures.recorded(
      manifest(
        startedAt = "2026-07-18T09:00:00Z",
        sessions = List(
          durable(stage = Some("Task"), lastActiveAt = "2026-07-18T09:05:00Z")
        )
      ),
      crashed = false
    )
    val rows =
      SessionPicker.sessionRows(
        SessionIndex.of(List(attempt2, attempt1)),
        expanded = true
      )
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
    val run = ManifestFixtures.recorded(
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
      SessionPicker
        .sessionRows(SessionIndex.of(List(run)), expanded = true)
        .map(_.label),
      List(
        "code-structure (reviewer) — stage Task: add auth [claude] (ephemeral)"
      )
    )

  test(
    "sessionRows omits the earlier-occurrences expander when there's only one occurrence"
  ):
    val run = ManifestFixtures.recorded(
      manifest(sessions = List(durable())),
      crashed = false
    )
    assertEquals(
      SessionPicker
        .sessionRows(SessionIndex.of(List(run)), expanded = false)
        .map(_.label),
      List("★ main — latest (no stage yet) [claude]")
    )

  test("sessionRows singularises a count of 1 in the expander label"):
    val run = ManifestFixtures.recorded(
      manifest(sessions = List(durable(), ephemeral())),
      crashed = false
    )
    assertEquals(
      SessionPicker
        .sessionRows(SessionIndex.of(List(run)), expanded = false)
        .map(_.label),
      List(
        "★ main — latest (no stage yet) [claude]",
        "… show 1 ephemeral session (reviews, plan steps)"
      )
    )

  test(
    "sessionRows keeps two sessions sharing a name apart by their minting stage"
  ):
    val run = ManifestFixtures.recorded(
      manifest(sessions =
        List(
          durable(
            agent = "coder",
            sessionName = "implementer",
            sessionStage = StagePath.FlowBody.child("Task: parse the input", 0),
            stage = Some("Task: parse the input"),
            lastActiveAt = "2026-07-18T09:00:00Z"
          ),
          durable(
            agent = "coder",
            sessionName = "implementer",
            sessionStage = StagePath.FlowBody.child("Task: wire the parser", 0),
            stage = Some("Task: wire the parser"),
            lastActiveAt = "2026-07-18T09:05:00Z"
          )
        )
      ),
      crashed = false
    )
    assertEquals(
      SessionPicker
        .sessionRows(SessionIndex.of(List(run)), expanded = false)
        .map(_.label),
      List(
        "★ implementer — latest (stage: Task: wire the parser) [claude]",
        "★ implementer — latest (stage: Task: parse the input) [claude]"
      )
    )

  test(
    "sessionRows groups durable lineages by (agent, sessionName), not agent alone"
  ):
    val run = ManifestFixtures.recorded(
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
    val rows =
      SessionPicker.sessionRows(SessionIndex.of(List(run)), expanded = false)
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
      ManifestFixtures.recorded(
        manifest(branch = Some(b), sessions = List(durable(lastActiveAt = at))),
        crashed = false
      )
    assertEquals(
      SessionPicker
        .sessionRows(SessionIndex.of(attempts), expanded = false)
        .map(_.label),
      List(
        "★ main — latest (no stage yet) [claude] on feat-a",
        "★ main — latest (no stage yet) [claude] on feat-b"
      )
    )

  test("sessionRows groups resumed attempts on one branch into one lineage"):
    val attempts = List("2026-07-18T10:00:00Z", "2026-07-18T09:00:00Z").map:
      at =>
        ManifestFixtures.recorded(
          manifest(
            branch = Some("feat-a"),
            sessions = List(durable(lastActiveAt = at))
          ),
          crashed = false
        )
    assertEquals(
      SessionPicker
        .sessionRows(SessionIndex.of(attempts), expanded = false)
        .map(_.label),
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
      ManifestFixtures.recorded(
        manifest(
          startedAt = startedAt,
          branch = Some("feat-a"),
          sessions = sessions
        ),
        crashed = false
      )
    assertEquals(
      SessionPicker
        .sessionRows(SessionIndex.of(attempts), expanded = true)
        .map(_.label)
        .tail,
      List(
        "main [claude] (earlier occurrence) on feat-a",
        "security [claude] (ephemeral) on feat-a"
      )
    )

  test("sessionRows suffixes a crashed attempt's rows with `(crashed)`"):
    val run =
      ManifestFixtures.recorded(
        manifest(sessions = List(durable())),
        crashed = true
      )
    assertEquals(
      SessionPicker
        .sessionRows(SessionIndex.of(List(run)), expanded = false)
        .map(_.label),
      List("★ main — latest (no stage yet) [claude] (crashed)")
    )

  test("sessionRows disables a wireId-less session, naming its harness"):
    val run = ManifestFixtures.recorded(
      manifest(sessions =
        List(durable(backend = BackendTag.Pi, wireId = None))
      ),
      crashed = false
    )
    assertEquals(
      SessionPicker
        .sessionRows(SessionIndex.of(List(run)), expanded = false)
        .map(_.disabledReason),
      List(Some("pi session has no resumable id"))
    )

  test("sessionRows enables a claude session with a wireId"):
    val run =
      ManifestFixtures.recorded(
        manifest(sessions = List(durable())),
        crashed = false
      )
    assertEquals(
      SessionPicker
        .sessionRows(SessionIndex.of(List(run)), expanded = false)
        .map(_.disabledReason),
      List(None)
    )

  test(
    "sessionRows is a silent no-op shape on an empty run list (no rows, no crash)"
  ):
    assertEquals(
      SessionPicker.sessionRows(SessionIndex.of(Nil), expanded = false),
      Nil
    )
    assertEquals(
      SessionPicker.sessionRows(SessionIndex.of(Nil), expanded = true),
      Nil
    )
