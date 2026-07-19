package orca.shell

import orca.runner.{ManifestSession, RunManifest}
import orca.shell.sessions.ReadRun
import orca.testkit.TempDirs

class MainTest extends munit.FunSuite:

  private def manifest(
      workDir: String = "/work",
      sessions: List[ManifestSession]
  ): RunManifest =
    RunManifest(
      orcaVersion = "0.0.test",
      flow = Some("a-flow.sc"),
      workDir = workDir,
      pid = 1,
      startedAt = "2026-07-18T10:00:00Z",
      finishedAt = None,
      outcome = "succeeded",
      sessions = sessions
    )

  private def session(
      harness: String = "ClaudeCode",
      wireId: Option[String] = Some("uuid"),
      reason: Option[String] = None,
      agent: String = "coder",
      role: Option[String] = None,
      stage: Option[String] = None
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
      firstSeenAt = "2026-07-18T10:00:00Z",
      lastActiveAt = "2026-07-18T10:00:00Z"
    )

  test(
    "sessionRows lists sessions in the given runs' order, one row per session"
  ):
    val run1 =
      ReadRun(manifest(sessions = List(session(agent = "a1"))), crashed = false)
    val run2 = ReadRun(
      manifest(sessions = List(session(agent = "a2"), session(agent = "a3"))),
      crashed = false
    )
    val rows = Main.sessionRows(List(run1, run2))
    assertEquals(rows.map(_.value.session.agent), List("a1", "a2", "a3"))

  test(
    "sessionRows labels with agent, role, stage, and the harness settings name"
  ):
    val run = ReadRun(
      manifest(sessions =
        List(
          session(
            agent = "coder",
            role = Some("reviewer"),
            stage = Some("implement")
          )
        )
      ),
      crashed = false
    )
    assertEquals(
      Main.sessionRows(List(run)).map(_.label),
      List("coder (reviewer) — stage implement [claude]")
    )

  test("sessionRows omits absent role/stage segments"):
    val run = ReadRun(
      manifest(sessions = List(session(agent = "coder"))),
      crashed = false
    )
    assertEquals(
      Main.sessionRows(List(run)).map(_.label),
      List("coder [claude]")
    )

  test("sessionRows suffixes a crashed run's rows with `(crashed)`"):
    val run = ReadRun(
      manifest(sessions = List(session(agent = "coder"))),
      crashed = true
    )
    assertEquals(
      Main.sessionRows(List(run)).map(_.label),
      List("coder [claude] (crashed)")
    )

  test(
    "sessionRows falls back to the raw harness string for an unrecognised one"
  ):
    val run = ReadRun(
      manifest(sessions = List(session(harness = "SomeFutureHarness"))),
      crashed = false
    )
    assertEquals(
      Main.sessionRows(List(run)).map(_.label),
      List("coder [SomeFutureHarness]")
    )

  test("sessionRows disables a wireId-less session with its stored reason"):
    val reason = "pi sessions are deleted when the run's temp dir is reclaimed"
    val run = ReadRun(
      manifest(sessions =
        List(session(harness = "Pi", wireId = None, reason = Some(reason)))
      ),
      crashed = false
    )
    assertEquals(
      Main.sessionRows(List(run)).map(_.disabledReason),
      List(Some(reason))
    )

  test("sessionRows disables an unrecognised harness"):
    val run = ReadRun(
      manifest(sessions = List(session(harness = "SomeFutureHarness"))),
      crashed = false
    )
    assert(Main.sessionRows(List(run)).head.disabledReason.isDefined)

  test("sessionRows enables a claude session with a wireId"):
    val run = ReadRun(manifest(sessions = List(session())), crashed = false)
    assertEquals(Main.sessionRows(List(run)).map(_.disabledReason), List(None))

  test(
    "sessionRows enables a gemini session with a wireId, deferring the real index lookup to selection"
  ):
    val run = ReadRun(
      manifest(sessions =
        List(session(harness = "Gemini", wireId = Some("uuid")))
      ),
      crashed = false
    )
    assertEquals(Main.sessionRows(List(run)).map(_.disabledReason), List(None))

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
