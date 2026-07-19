package orca.shell.actions

import orca.runner.{ManifestSession, RunManifest}
import orca.shell.sessions.SessionSelection
import orca.testkit.TempDirs

class SessionActionTest extends munit.FunSuite:

  private def session(stage: Option[String] = None): ManifestSession =
    ManifestSession(
      harness = "ClaudeCode",
      wireId = Some("uuid"),
      resumable = true,
      reason = None,
      agent = "main",
      role = None,
      stage = stage,
      sessionName = Some("newest"),
      kind = "durable",
      firstSeenAt = "2026-07-18T09:00:00Z",
      lastActiveAt = "2026-07-18T09:45:00Z"
    )

  private def manifest(s: ManifestSession): RunManifest =
    RunManifest(
      orcaVersion = "0.0.test",
      flow = Some("a-flow.sc"),
      workDir = "/work",
      pid = 1,
      startedAt = "2026-07-18T09:00:00Z",
      finishedAt = None,
      outcome = "succeeded",
      sessions = List(s)
    )

  test("identityNotice: names the session, harness, and workDir"):
    val s = session()
    assertEquals(
      SessionAction.identityNotice(
        SessionSelection(manifest(s), s, crashed = false),
        "claude"
      ),
      "resuming session 'newest' [claude], in /work"
    )

  test("identityNotice: includes the stage when the session has one"):
    val s = session(stage = Some("Task: fix a bug"))
    assertEquals(
      SessionAction.identityNotice(
        SessionSelection(manifest(s), s, crashed = false),
        "claude"
      ),
      "resuming session 'newest' [claude], stage 'Task: fix a bug', in /work"
    )

  test("validatedWorkDir accepts a path that's still a directory"):
    val dir = TempDirs.dir()
    assertEquals(SessionAction.validatedWorkDir(dir.toString), Right(dir))

  test(
    "validatedWorkDir rejects a relative/malformed path (os.Path's IllegalArgumentException)"
  ):
    assertEquals(
      SessionAction.validatedWorkDir("not-an-absolute-path"),
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
      SessionAction.validatedWorkDir(dir.toString),
      Left(s"the recorded working directory $dir no longer exists")
    )
