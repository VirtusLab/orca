package orca.shell.actions

import orca.runner.manifest.{AttemptManifest, ManifestSession}
import orca.shell.sessions.ManifestFixtures.{durable, manifest}
import orca.shell.sessions.SessionSelection
import orca.testkit.TempDirs
import orca.tools.HeadState
import orca.tools.pi.PiSessionStore

class SessionActionTest extends munit.FunSuite:

  private def session(stage: Option[String] = None): ManifestSession =
    durable(
      sessionName = "newest",
      sessionStage = "Task: fix a bug#0",
      stage = stage,
      lastActiveAt = "2026-07-18T09:45:00Z"
    )

  private def manifestOf(
      s: ManifestSession,
      branch: Option[String] = None
  ): AttemptManifest =
    manifest(
      startedAt = "2026-07-18T09:00:00Z",
      sessions = List(s),
      branch = branch
    )

  private def noticeOnBranch(head: Option[HeadState]): String =
    val s = session()
    SessionAction.identityNotice(
      SessionSelection(manifestOf(s, Some("feat/x")), s, crashed = false),
      "claude",
      head
    )

  // `continue <name>` picks the newest of the sessions sharing a name, so this
  // line is where the user sees which one it landed on.
  test("identityNotice: names the session, harness, stage, and workDir"):
    val s = session()
    assertEquals(
      SessionAction.identityNotice(
        SessionSelection(manifestOf(s), s, crashed = false),
        "claude",
        head = Some(HeadState.OnBranch("main"))
      ),
      "resuming session 'newest' [claude], in /work"
    )

  test("identityNotice: includes the stage when the session has one"):
    val s = session(stage = Some("Task: fix a bug"))
    assertEquals(
      SessionAction.identityNotice(
        SessionSelection(manifestOf(s), s, crashed = false),
        "claude",
        head = None
      ),
      "resuming session 'newest' [claude], stage 'Task: fix a bug', in /work"
    )

  test("identityNotice: names the recorded branch"):
    assertEquals(
      noticeOnBranch(head = Some(HeadState.OnBranch("feat/x"))),
      "resuming session 'newest' [claude], on branch 'feat/x', in /work"
    )

  test("identityNotice: warns when workDir is now on another branch"):
    assertEquals(
      noticeOnBranch(head = Some(HeadState.OnBranch("main"))),
      "resuming session 'newest' [claude], on branch 'feat/x', in /work — warning: /work is now on 'main'"
    )

  test("identityNotice: warns when workDir is now on a detached HEAD"):
    assertEquals(
      noticeOnBranch(head = Some(HeadState.Detached)),
      "resuming session 'newest' [claude], on branch 'feat/x', in /work — warning: /work is now on a detached HEAD"
    )

  test("identityNotice: no warning when the current branch is unknown"):
    assertEquals(
      noticeOnBranch(head = None),
      "resuming session 'newest' [claude], on branch 'feat/x', in /work"
    )

  private def piDir(workDir: os.Path, id: String): os.Path =
    PiSessionStore.dirFor(workDir, id).get

  test("piSessionDir resolves a session dir that still holds a transcript"):
    val workDir = TempDirs.dir()
    val dir = piDir(workDir, "a-session")
    os.write(
      dir / "session.jsonl",
      s"""{"type":"session","id":"a-session","cwd":"$workDir"}""" + "\n",
      createFolders = true
    )
    assertEquals(
      SessionAction.piSessionDir("a-session", workDir),
      Right(dir)
    )

  test(
    "piSessionDir reports a pruned or never-written session, naming the dir"
  ):
    val workDir = TempDirs.dir()
    val Left(reason) =
      SessionAction.piSessionDir("a-session", workDir): @unchecked
    assert(reason.contains(piDir(workDir, "a-session").toString), reason)

  test("piSessionDir reports an id that isn't a directory name separately"):
    val workDir = TempDirs.dir()
    val Left(reason) =
      SessionAction.piSessionDir("../../etc", workDir): @unchecked
    assert(reason.contains("not a directory name"), reason)

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
