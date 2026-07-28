package orca.tools.pi

import orca.testkit.TempDirs

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
    // The escape target exists, so an id resolved naively would point at a real
    // directory and read as resumable.
    val workDir = TempDirs.dir()
    os.write(
      workDir / ".orca" / "etc" / "session.jsonl",
      "{}\n",
      createFolders = true
    )
    assertEquals(PiSessionStore.dirFor(workDir, "../../etc"), None)

  test("hasTranscript is true for a dir holding a .jsonl"):
    val dir = TempDirs.dir()
    os.write(dir / "session.jsonl", "{}\n")
    assert(PiSessionStore.hasTranscript(dir))

  test("hasTranscript is false for a dir holding only non-transcript files"):
    val dir = TempDirs.dir()
    os.write(dir / "notes.txt", "x")
    assert(!PiSessionStore.hasTranscript(dir))

  test("hasTranscript is false for an empty dir"):
    assert(!PiSessionStore.hasTranscript(TempDirs.dir()))

  test("hasTranscript is false for a dir that isn't there"):
    val dir = TempDirs.dir()
    os.remove.all(dir)
    assert(!PiSessionStore.hasTranscript(dir))
