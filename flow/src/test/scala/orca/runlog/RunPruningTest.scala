package orca.runlog

import orca.{AttemptId, OrcaDir, RunKey, StagePath}
import orca.agents.BackendTag
import orca.gitref.BranchName
import orca.testkit.TempDirs
import ox.tap

import java.time.Instant

class RunPruningTest extends munit.FunSuite:

  private val at = Instant.parse("2026-10-08T10:00:00Z")
  private val attempt = AttemptId(Instant.ofEpochMilli(1000), pid = 42)
  private val current = RunKey.of("current")

  private val minted = RunEvent.SessionMinted(
    at,
    attempt,
    "n",
    StagePath.FlowBody,
    "id",
    "seed",
    BackendTag.Pi
  )
  private val succeeded = RunEvent.RunSucceeded(
    at,
    attempt,
    BranchName.parse("b").fold(e => fail(e), identity),
    None
  )
  private val started =
    RunEvent.StageStarted(at, attempt, StagePath.FlowBody.child("s", 0))

  /** A run directory named `name` whose event log holds `events` and was last
    * modified at `mtime`.
    */
  private def run(
      cacheRuns: os.Path,
      name: String,
      mtime: Long,
      events: RunEvent*
  ): os.Path =
    val dir = cacheRuns / name
    val log = dir / OrcaDir.EventLogName
    os.write(
      log,
      events.map(RunEvent.encodeLine(_) + "\n"),
      createFolders = true
    )
    os.mtime.set(log, mtime): Unit
    dir

  /** `count` runs prefixed `prefix`, the first the newest, all modified after
    * `newerThan`.
    */
  private def runs(
      cacheRuns: os.Path,
      prefix: String,
      count: Int,
      newerThan: Long,
      events: RunEvent*
  ): List[os.Path] =
    (0 until count).toList.map: i =>
      run(cacheRuns, s"$prefix$i", newerThan + (count - i) * 1000L, events*)

  test("keeps the current run's directory"):
    val cacheRuns = TempDirs.dir()
    runs(cacheRuns, "new", 21, newerThan = 100_000, started): Unit
    val currentDir = cacheRuns / current.value
    os.makeDir(currentDir)
    RunPruning.pruneRuns(cacheRuns, current)
    assert(os.exists(currentDir))

  test("keeps a run with a session minted after its last success"):
    val cacheRuns = TempDirs.dir()
    runs(cacheRuns, "done", 21, newerThan = 100_000, minted, succeeded): Unit
    val resumed = run(cacheRuns, "resumed", 1000, succeeded, minted)
    val neverSucceeded = run(cacheRuns, "neverSucceeded", 1000, minted)
    RunPruning.pruneRuns(cacheRuns, current)
    assert(os.exists(resumed))
    assert(os.exists(neverSucceeded))
    assert(!os.exists(cacheRuns / "done20"))

  test("keeps the newest 20 runs with sessions and the newest 20 of any kind"):
    val cacheRuns = TempDirs.dir()
    val plain = runs(cacheRuns, "plain", 25, newerThan = 1_000_000, started)
    val sessions =
      runs(cacheRuns, "sessions", 25, newerThan = 100_000, minted, succeeded)
    RunPruning.pruneRuns(cacheRuns, current)
    assertEquals(
      os.list(cacheRuns).toSet,
      (plain.take(20) ++ sessions.take(20)).toSet
    )

  test("a run whose event log does not decode has no sessions"):
    val cacheRuns = TempDirs.dir()
    runs(cacheRuns, "new", 20, newerThan = 100_000, started): Unit
    val corrupt = cacheRuns / "corrupt"
    os.write(corrupt / OrcaDir.EventLogName, "not json\n", createFolders = true)
    os.mtime.set(corrupt / OrcaDir.EventLogName, 1000): Unit
    RunPruning.pruneRuns(cacheRuns, current)
    assert(!os.exists(corrupt))

  test("a run without an event log is the oldest"):
    val cacheRuns = TempDirs.dir()
    runs(cacheRuns, "old", 20, newerThan = 0, started): Unit
    val empty = cacheRuns / "empty"
    os.makeDir(empty)
    RunPruning.pruneRuns(cacheRuns, current)
    assert(!os.exists(empty))

  test(
    "keeps the trace logs of the newest 40 attempts across runs, rolled parts included"
  ):
    val cacheRuns = TempDirs.dir()
    val runDirs = List(cacheRuns / "a", cacheRuns / "b")
    val logs = runDirs.map(dir =>
      (dir / OrcaDir.EventLogName).tap(os.write(_, "", createFolders = true))
    )
    // Attempts alternate between the two runs, so the oldest is in run `a`.
    val traces = (0 until 41).toList.map: i =>
      val id = AttemptId(Instant.ofEpochMilli(1_000_000L + i), pid = 7)
      List(OrcaDir.TraceLogSuffix, OrcaDir.RolledTraceLogSuffix).map: suffix =>
        (runDirs(i % 2) / s"${id.value}$suffix").tap(os.write(_, ""))
    RunPruning.pruneTraces(cacheRuns)
    assertEquals(
      runDirs.flatMap(os.list(_)).toSet,
      traces.drop(1).flatten.toSet ++ logs
    )

  test("removing legacy files leaves the run directories alone"):
    val cache = TempDirs.dir()
    os.write(
      cache / "attempts" / "1000-42.manifest.json",
      "{}",
      createFolders = true
    )
    os.write(cache / "runs" / "abc.sessions.json", "[]", createFolders = true)
    val runDir = run(cache / "runs", "abc", 1000, started)
    RunPruning.removeLegacy(cache)
    assertEquals(os.list(cache).toList, List(cache / "runs"))
    assertEquals(os.list(cache / "runs").toList, List(runDir))
    assert(os.exists(runDir / OrcaDir.EventLogName))
