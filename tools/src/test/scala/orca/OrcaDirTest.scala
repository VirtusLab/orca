package orca

import ox.discard
import orca.testkit.TempDirs

class OrcaDirTest extends munit.FunSuite:

  test("ensureCache creates the cache dir with exact marker file contents"):
    val wd = TempDirs.dir()
    val cache = OrcaDir.ensureCache(wd)
    assertEquals(cache, wd / ".orca" / "cache")
    assert(os.isDir(cache))
    assertEquals(
      os.read(cache / ".gitignore"),
      "# Automatically created by orca.\n*\n"
    )
    assertEquals(
      os.read(cache / "CACHEDIR.TAG"),
      "Signature: 8a477f597d28d172789f06886806bc55\n" +
        "# This file marks .orca/cache as a cache directory, so backup tools skip it.\n"
    )

  test("second ensureCache call leaves existing marker files untouched"):
    val wd = TempDirs.dir()
    val cache = OrcaDir.ensureCache(wd)
    // Surviving canaries prove the files are written only when absent.
    os.write.over(cache / ".gitignore", "canary-gitignore")
    os.write.over(cache / "CACHEDIR.TAG", "canary-tag")
    assertEquals(OrcaDir.ensureCache(wd), cache)
    assertEquals(os.read(cache / ".gitignore"), "canary-gitignore")
    assertEquals(os.read(cache / "CACHEDIR.TAG"), "canary-tag")

  test("ensureRoot creates .orca only, without the cache dir"):
    val wd = TempDirs.dir()
    val root = OrcaDir.ensureRoot(wd)
    assertEquals(root, wd / ".orca")
    assert(os.isDir(root))
    assert(!os.exists(root / "cache"))

  test("worktreesPath points at .orca/worktrees without creating it"):
    val wd = TempDirs.dir()
    assertEquals(OrcaDir.worktreesPath(wd), wd / ".orca" / "worktrees")
    assert(!os.exists(wd / ".orca"))

  test("ensureWorktrees writes the .gitignore but no CACHEDIR.TAG"):
    val wd = TempDirs.dir()
    val worktrees = OrcaDir.ensureWorktrees(wd)
    assertEquals(worktrees, wd / ".orca" / "worktrees")
    assertEquals(
      os.read(worktrees / ".gitignore"),
      "# Automatically created by orca.\n*\n"
    )
    assert(!os.exists(worktrees / "CACHEDIR.TAG"))

  test("ensureWorktrees aborts on a symlinked .orca/worktrees"):
    val wd = TempDirs.dir()
    val outside = TempDirs.dir() / "outside-worktrees"
    os.makeDir.all(outside)
    os.makeDir.all(OrcaDir.rootPath(wd))
    os.symlink(OrcaDir.worktreesPath(wd), outside)
    intercept[OrcaFlowException](OrcaDir.ensureWorktrees(wd)).discard
    assert(os.list(outside).isEmpty, "no write must go through the symlink")

  test("flowsPath points at .orca/flows without creating it"):
    val wd = TempDirs.dir()
    assertEquals(OrcaDir.flowsPath(wd), wd / ".orca" / "flows")
    assert(!os.exists(wd / ".orca"))

  test("ensureFlows creates .orca/flows and is idempotent"):
    val wd = TempDirs.dir()
    val flows = OrcaDir.ensureFlows(wd)
    assertEquals(flows, wd / ".orca" / "flows")
    assert(os.isDir(flows))
    assertEquals(OrcaDir.ensureFlows(wd), flows)

  test("ensureFlows aborts on a symlinked .orca/flows"):
    val wd = TempDirs.dir()
    val outside = TempDirs.dir() / "outside-flows"
    os.makeDir.all(outside)
    os.makeDir.all(OrcaDir.rootPath(wd))
    os.symlink(OrcaDir.rootPath(wd) / "flows", outside)
    intercept[OrcaFlowException](OrcaDir.ensureFlows(wd)).discard
    assert(os.list(outside).isEmpty, "no write must go through the symlink")

  test("assertNoOrcaSymlinks is a no-op when .orca doesn't exist"):
    val wd = TempDirs.dir()
    OrcaDir.assertNoOrcaSymlinks(wd, OrcaDir.flowsPath(wd))
    assert(
      !os.exists(OrcaDir.rootPath(wd)),
      "must not create .orca as a side effect"
    )

  test("assertNoOrcaSymlinks passes silently for a real .orca/flows dir"):
    val wd = TempDirs.dir()
    OrcaDir.ensureFlows(wd).discard
    OrcaDir.assertNoOrcaSymlinks(wd, OrcaDir.flowsPath(wd))

  test("assertNoOrcaSymlinks aborts on a symlinked .orca/flows"):
    val wd = TempDirs.dir()
    val outside = TempDirs.dir() / "outside-flows"
    os.makeDir.all(outside)
    os.makeDir.all(OrcaDir.rootPath(wd))
    os.symlink(OrcaDir.rootPath(wd) / "flows", outside)
    val ex = intercept[OrcaFlowException](
      OrcaDir.assertNoOrcaSymlinks(wd, OrcaDir.flowsPath(wd))
    )
    assert(ex.getMessage.contains("symlink"), ex.getMessage)

  test("cacheRunsPath creates .orca/cache/runs, including the cache dir"):
    val wd = TempDirs.dir()
    val runs = OrcaDir.cacheRunsPath(wd)
    assertEquals(runs, wd / ".orca" / "cache" / "runs")
    assert(os.isDir(runs))
    assert(os.isDir(wd / ".orca" / "cache"))

  test("runsPath points at .orca/cache/runs without creating anything"):
    val wd = TempDirs.dir()
    assertEquals(OrcaDir.runsPath(wd), wd / ".orca" / "cache" / "runs")
    assert(!os.exists(wd / ".orca"))

  test(
    "ensurePiSessions creates .orca/cache/pi-sessions, including the cache markers"
  ):
    val wd = TempDirs.dir()
    val sessions = OrcaDir.ensurePiSessions(wd)
    assertEquals(sessions, wd / ".orca" / "cache" / "pi-sessions")
    assert(os.isDir(sessions))
    assert(os.exists(wd / ".orca" / "cache" / ".gitignore"))

  test("ensurePiSessions aborts on a symlinked .orca/cache/pi-sessions"):
    val wd = TempDirs.dir()
    val outside = TempDirs.dir() / "outside-sessions"
    os.makeDir.all(outside)
    OrcaDir.ensureCache(wd).discard
    os.symlink(OrcaDir.piSessionsPath(wd), outside)
    intercept[OrcaFlowException](OrcaDir.ensurePiSessions(wd)).discard

  test(
    "piSessionsPath points at .orca/cache/pi-sessions without creating anything"
  ):
    val wd = TempDirs.dir()
    assertEquals(
      OrcaDir.piSessionsPath(wd),
      wd / ".orca" / "cache" / "pi-sessions"
    )
    assert(!os.exists(wd / ".orca"))
