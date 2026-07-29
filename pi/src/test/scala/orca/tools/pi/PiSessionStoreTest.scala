package orca.tools.pi

import orca.testkit.TempDirs

import java.time.Instant

class PiSessionStoreTest extends munit.FunSuite:

  test(
    "dirFor places a session under .orca/cache/pi-sessions, creating nothing"
  ):
    val workDir = TempDirs.dir()
    assertEquals(
      PiSessionStore.dirFor(workDir, "a-session"),
      Some(workDir / ".orca" / "cache" / "pi-sessions" / "a-session")
    )
    assert(!os.exists(workDir / ".orca"))

  test("dirFor refuses an id that would escape the store"):
    assertEquals(PiSessionStore.dirFor(TempDirs.dir(), "../../etc"), None)

  private def sessionDir(workDir: os.Path): os.Path =
    val dir = PiSessionStore.dirFor(workDir, "a-session").get
    os.makeDir.all(dir)
    dir

  /** The header line pi's `--continue` requires: first line of a `*.jsonl`,
    * `type == "session"`, a string `id`, and a `cwd` resolving to the process
    * cwd (which orca sets to the workDir). Shape captured verbatim (fields and
    * order) from a transcript the installed pi CLI wrote on this machine.
    */
  private def header(cwd: os.Path): String =
    s"""{"type":"session","version":3,"id":"019f414d-95df-7b8a-999a-1e163380ef0e","timestamp":"2026-07-08T10:37:11.519Z","cwd":"$cwd"}""" + "\n"

  test("resumable for a transcript whose header cwd matches the workDir"):
    val workDir = TempDirs.dir()
    val dir = sessionDir(workDir)
    os.write(dir / "session.jsonl", header(workDir))
    assert(PiSessionStore.resumable(dir, workDir, Instant.now()))

  test("not resumable when the header cwd names a different checkout"):
    // pi's `--continue` filters transcripts by header cwd and would silently
    // start an empty session here — orca must report absence instead.
    val workDir = TempDirs.dir()
    val dir = sessionDir(workDir)
    os.write(dir / "session.jsonl", header(TempDirs.dir()))
    assert(!PiSessionStore.resumable(dir, workDir, Instant.now()))

  test("not resumable when the transcript has no parseable session header"):
    val workDir = TempDirs.dir()
    val dir = sessionDir(workDir)
    os.write(dir / "session.jsonl", "{}\n")
    assert(!PiSessionStore.resumable(dir, workDir, Instant.now()))

  test("not resumable when the dir holds only non-transcript files"):
    val workDir = TempDirs.dir()
    val dir = sessionDir(workDir)
    os.write(dir / "notes.txt", "x")
    assert(!PiSessionStore.resumable(dir, workDir, Instant.now()))

  test("not resumable when the dir isn't there"):
    val workDir = TempDirs.dir()
    val dir = PiSessionStore.dirFor(workDir, "a-session").get
    assert(!PiSessionStore.resumable(dir, workDir, Instant.now()))
