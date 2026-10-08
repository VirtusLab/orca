package orca

import ox.discard
import orca.testkit.TempDirs

import java.time.Instant

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

  test("a committed file's replace leaves only the file in its directory"):
    val wd = TempDirs.dir()
    val file = OrcaDir.progressFile(wd, RunKey.of("p"))
    file.replace("{}")
    assertEquals(os.list(OrcaDir.runsPath(wd)).toList, List(file.path))

  test("progressFile aborts on a symlinked .orca/runs"):
    val wd = TempDirs.dir()
    val outside = TempDirs.dir() / "outside-runs"
    os.makeDir.all(outside)
    os.makeDir.all(OrcaDir.rootPath(wd))
    os.symlink(OrcaDir.runsPath(wd), outside)
    intercept[OrcaFlowException](
      OrcaDir.progressFile(wd, RunKey.of("p"))
    ).discard

  test("runsPath points at .orca/runs without creating anything"):
    val wd = TempDirs.dir()
    assertEquals(OrcaDir.runsPath(wd), wd / ".orca" / "runs")
    assert(!os.exists(wd / ".orca"))

  test("progressPath is .orca/runs/<key>.progress.json"):
    val wd = TempDirs.dir()
    assertEquals(
      OrcaDir.progressPath(wd, RunKey.of("p")),
      wd / ".orca" / "runs" / s"${RunKey.of("p").value}.progress.json"
    )

  test("settingsFile's replace swaps a symlink for a file, target untouched"):
    val wd = TempDirs.dir()
    val outside = TempDirs.dir() / "outside.properties"
    os.write(outside, "kept")
    os.makeDir.all(OrcaDir.rootPath(wd))
    os.symlink(OrcaDir.settingsPath(wd), outside)
    OrcaDir.settingsFile(wd).replace("format = x\n")
    assert(!os.isLink(OrcaDir.settingsPath(wd)))
    assertEquals(os.read(OrcaDir.settingsPath(wd)), "format = x\n")
    assertEquals(os.read(outside), "kept")

  test("attemptsPath points at .orca/cache/attempts without creating anything"):
    val wd = TempDirs.dir()
    assertEquals(OrcaDir.attemptsPath(wd), wd / ".orca" / "cache" / "attempts")
    assert(!os.exists(wd / ".orca"))

  test("manifestPath is named after the attempt id"):
    val wd = TempDirs.dir()
    val id = AttemptId(Instant.ofEpochMilli(1700000000000L), 42L)
    assertEquals(
      OrcaDir.manifestPath(wd, id),
      wd / ".orca" / "cache" / "attempts" / "1700000000000-42.manifest.json"
    )

  test("attemptIdOf maps a trace log and its rolled part to their attempt"):
    val wd = TempDirs.dir()
    val key = RunKey.of("p")
    val id = AttemptId(Instant.ofEpochMilli(1700000000000L), 42L)
    val rolled = os.Path(
      OrcaDir
        .traceLogRollPattern(wd, key, id)
        .replace("%i", OrcaDir.TraceLogRollIndex.toString)
    )
    assertEquals(
      List(OrcaDir.traceLogPath(wd, key, id), rolled).map(OrcaDir.attemptIdOf),
      List(Some(id), Some(id))
    )

  test("run-dir paths sit under .orca/cache/runs/<key> and create nothing"):
    val wd = TempDirs.dir()
    val key = RunKey.of("p")
    val runDir = wd / ".orca" / "cache" / "runs" / key.value
    val id = AttemptId(Instant.ofEpochMilli(1700000000000L), 42L)
    assertEquals(OrcaDir.cacheRunsPath(wd), wd / ".orca" / "cache" / "runs")
    assertEquals(OrcaDir.runDirPath(wd, key), runDir)
    assertEquals(OrcaDir.eventLogPath(wd, key), runDir / "events.jsonl")
    assertEquals(
      OrcaDir.traceLogPath(wd, key, id),
      runDir / "1700000000000-42.trace.log"
    )
    assertEquals(
      OrcaDir.traceLogRollPattern(wd, key, id),
      (runDir / "1700000000000-42.trace.%i.log").toString
    )
    assert(!os.exists(wd / ".orca"))

  test("ensureRunDir creates .orca/cache/runs/<key>, with the cache markers"):
    val wd = TempDirs.dir()
    val key = RunKey.of("p")
    val runDir = OrcaDir.ensureRunDir(wd, key)
    assertEquals(runDir, OrcaDir.runDirPath(wd, key))
    assert(os.isDir(runDir))
    assert(os.exists(wd / ".orca" / "cache" / ".gitignore"))
    assert(os.exists(wd / ".orca" / "cache" / "CACHEDIR.TAG"))

  test("ensureRunDir aborts on a symlinked .orca/cache/runs/<key>"):
    val wd = TempDirs.dir()
    val key = RunKey.of("p")
    val outside = TempDirs.dir() / "outside-run"
    os.makeDir.all(outside)
    os.makeDir.all(OrcaDir.cacheRunsPath(wd))
    os.symlink(OrcaDir.runDirPath(wd, key), outside)
    intercept[OrcaFlowException](OrcaDir.ensureRunDir(wd, key)).discard

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
