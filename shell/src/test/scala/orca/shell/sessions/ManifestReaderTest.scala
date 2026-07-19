package orca.shell.sessions

import orca.OrcaFlowException
import orca.testkit.TempDirs

class ManifestReaderTest extends munit.FunSuite:

  private val alwaysDead: Long => Boolean = _ => false
  private val alwaysAlive: Long => Boolean = _ => true

  private def runsDir(workDir: os.Path): os.Path =
    workDir / ".orca" / "cache" / "runs"

  private def writeManifest(
      workDir: os.Path,
      name: String,
      startedAt: String,
      manifestVersion: Int = 1,
      pid: Long = 111,
      outcome: String = "succeeded"
  ): Unit =
    val json =
      s"""{
         |  "manifestVersion": $manifestVersion,
         |  "orcaVersion": "0.0.test",
         |  "workDir": "${workDir.toString}",
         |  "pid": $pid,
         |  "startedAt": "$startedAt",
         |  "outcome": "$outcome",
         |  "sessions": []
         |}""".stripMargin
    os.write(runsDir(workDir) / name, json, createFolders = true)

  test("list returns (Nil, Nil) for an absent runs dir, creating nothing"):
    val workDir = TempDirs.dir()
    assertEquals(ManifestReader.list(workDir, alwaysDead), (Nil, Nil))
    assert(!os.exists(workDir / ".orca"), "reading must not create .orca")

  test("list returns (Nil, Nil) for an empty runs dir"):
    val workDir = TempDirs.dir()
    os.makeDir.all(runsDir(workDir))
    assertEquals(ManifestReader.list(workDir, alwaysDead), (Nil, Nil))

  test("list orders manifests newest-first by startedAt"):
    val workDir = TempDirs.dir()
    writeManifest(workDir, "a.json", startedAt = "2026-07-18T10:00:00Z")
    writeManifest(workDir, "b.json", startedAt = "2026-07-18T12:00:00Z")
    writeManifest(workDir, "c.json", startedAt = "2026-07-18T11:00:00Z")
    val (runs, warnings) = ManifestReader.list(workDir, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(
      runs.map(_.manifest.startedAt),
      List(
        "2026-07-18T12:00:00Z",
        "2026-07-18T11:00:00Z",
        "2026-07-18T10:00:00Z"
      )
    )

  test(
    "list skips a manifestVersion newer than this shell understands, warning by filename"
  ):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      "future.json",
      startedAt = "2026-07-18T10:00:00Z",
      manifestVersion = 2
    )
    val (runs, warnings) = ManifestReader.list(workDir, alwaysDead)
    assertEquals(runs, Nil)
    assertEquals(warnings.size, 1)
    assert(
      warnings.head.contains("future.json"),
      s"expected the filename in the warning, got: ${warnings.head}"
    )

  test("a running manifest with a dead pid is included and marked crashed"):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      "dead.json",
      startedAt = "2026-07-18T10:00:00Z",
      pid = 999999,
      outcome = "running"
    )
    val (runs, warnings) = ManifestReader.list(workDir, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(runs.map(_.crashed), List(true))

  test("a running manifest with a live pid is included and not marked crashed"):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      "alive.json",
      startedAt = "2026-07-18T10:00:00Z",
      pid = 1,
      outcome = "running"
    )
    val (runs, warnings) = ManifestReader.list(workDir, alwaysAlive)
    assertEquals(warnings, Nil)
    assertEquals(runs.map(_.crashed), List(false))

  test("a finished manifest is never marked crashed, even with a dead pid"):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      "done.json",
      startedAt = "2026-07-18T10:00:00Z",
      pid = 999999,
      outcome = "succeeded"
    )
    val (runs, _) = ManifestReader.list(workDir, alwaysDead)
    assertEquals(runs.map(_.crashed), List(false))

  test(
    "list skips a manifest with an unparseable startedAt, warning by filename"
  ):
    val workDir = TempDirs.dir()
    writeManifest(workDir, "badstart.json", startedAt = "not-a-timestamp")
    val (runs, warnings) = ManifestReader.list(workDir, alwaysDead)
    assertEquals(runs, Nil)
    assertEquals(warnings.size, 1)
    assert(
      warnings.head.contains("badstart.json"),
      s"expected the filename in the warning, got: ${warnings.head}"
    )

  test("list skips unparseable JSON, warning by filename"):
    val workDir = TempDirs.dir()
    os.write(
      runsDir(workDir) / "garbage.json",
      "{ this is not json",
      createFolders = true
    )
    val (runs, warnings) = ManifestReader.list(workDir, alwaysDead)
    assertEquals(runs, Nil)
    assertEquals(warnings.size, 1)
    assert(
      warnings.head.contains("garbage.json"),
      s"expected the filename in the warning, got: ${warnings.head}"
    )

  test("list aborts on a symlinked .orca/cache/runs"):
    val workDir = TempDirs.dir()
    val outside = TempDirs.dir() / "outside-runs"
    os.makeDir.all(outside)
    os.makeDir.all(workDir / ".orca" / "cache")
    os.symlink(runsDir(workDir), outside)
    val ex = intercept[OrcaFlowException](
      ManifestReader.list(workDir, alwaysDead)
    )
    assert(ex.getMessage.contains("symlink"), ex.getMessage)
