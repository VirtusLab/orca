package orca.shell.sessions

import orca.OrcaFlowException
import orca.testkit.TempDirs

import java.time.Instant

class ManifestReaderTest extends munit.FunSuite:

  private val alwaysDead: Long => Boolean = _ => false
  private val alwaysAlive: Long => Boolean = _ => true

  private def runsDir(workDir: os.Path): os.Path =
    workDir / ".orca" / "cache" / "runs"

  private def writeManifest(
      workDir: os.Path,
      name: String,
      startedAt: String,
      pid: Long = 111,
      outcome: String = "succeeded"
  ): Unit =
    val json =
      s"""{
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
    assertEquals(ManifestReader.list(workDir, Nil, alwaysDead), (Nil, Nil))
    assert(!os.exists(workDir / ".orca"), "reading must not create .orca")

  test("list returns (Nil, Nil) for an empty runs dir"):
    val workDir = TempDirs.dir()
    os.makeDir.all(runsDir(workDir))
    assertEquals(ManifestReader.list(workDir, Nil, alwaysDead), (Nil, Nil))

  test("list orders manifests newest-first by startedAt"):
    val workDir = TempDirs.dir()
    writeManifest(workDir, "a.json", startedAt = "2026-07-18T10:00:00Z")
    writeManifest(workDir, "b.json", startedAt = "2026-07-18T12:00:00Z")
    writeManifest(workDir, "c.json", startedAt = "2026-07-18T11:00:00Z")
    val (runs, warnings) = ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(
      runs.map(_.manifest.startedAt),
      List(
        Instant.parse("2026-07-18T12:00:00Z"),
        Instant.parse("2026-07-18T11:00:00Z"),
        Instant.parse("2026-07-18T10:00:00Z")
      )
    )

  /** A verbatim v2 manifest, unedited since that build wrote it: it predates
    * `cost`/`turns` and still carries `manifestVersion`. It only lists because
    * the version gate is gone (ADR 0021 §8 amendment, 2026-08-05) and because
    * the fields it lacks are no longer part of this schema.
    */
  test("a manifest body from an older build is listed, not skipped"):
    val workDir = TempDirs.dir()
    os.write(
      runsDir(workDir) / "v2.json",
      """{
        |  "manifestVersion": 2,
        |  "orcaVersion": "0.0.test",
        |  "workDir": "/work",
        |  "pid": 111,
        |  "startedAt": "2026-07-18T10:00:00Z",
        |  "outcome": "succeeded",
        |  "sessions": []
        |}""".stripMargin,
      createFolders = true
    )
    val (runs, warnings) = ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(
      runs.map(_.manifest.startedAt),
      List(Instant.parse("2026-07-18T10:00:00Z"))
    )
    assertEquals(warnings, Nil)

  test("a skipped manifest is warned about without sinking the listing"):
    val workDir = TempDirs.dir()
    writeManifest(workDir, "good.json", startedAt = "2026-07-18T11:00:00Z")
    os.write(
      runsDir(workDir) / "no-workdir.json",
      """{
        |  "orcaVersion": "0.0.test",
        |  "pid": 111,
        |  "startedAt": "2026-07-18T10:00:00Z",
        |  "outcome": "succeeded",
        |  "sessions": []
        |}""".stripMargin,
      createFolders = true
    )
    val (runs, warnings) = ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(
      runs.map(_.manifest.startedAt),
      List(Instant.parse("2026-07-18T11:00:00Z"))
    )
    assertEquals(warnings.size, 1)
    assert(
      warnings.head.contains("no-workdir.json"),
      s"expected the filename in the warning, got: ${warnings.head}"
    )

  /** `sessions` is the one collection the codec keeps strict: absent must not
    * read as empty, or the menu offers a "(0 session(s))" row leading nowhere.
    */
  test("list skips a manifest with no sessions array, warning by filename"):
    val workDir = TempDirs.dir()
    // `sessions` is the ONLY field omitted, so this fails for that reason and
    // no other — the point being that it must not read as an empty list.
    os.write(
      runsDir(workDir) / "no-sessions.json",
      """{
        |  "orcaVersion": "0.0.test",
        |  "workDir": "/work",
        |  "pid": 111,
        |  "startedAt": "2026-07-18T10:00:00Z",
        |  "outcome": "succeeded"
        |}""".stripMargin,
      createFolders = true
    )
    val (runs, warnings) = ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(runs, Nil)
    assertEquals(warnings.size, 1)
    assert(
      warnings.head.contains("no-sessions.json") &&
        warnings.head.contains("sessions"),
      s"expected the filename and field in the warning, got: ${warnings.head}"
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
    val (runs, warnings) = ManifestReader.list(workDir, Nil, alwaysDead)
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
    val (runs, warnings) = ManifestReader.list(workDir, Nil, alwaysAlive)
    assertEquals(warnings, Nil)
    assertEquals(runs.map(_.crashed), List(false))

  test(
    "a manifest with an unrecognised outcome and a dead pid is not marked crashed"
  ):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      "unknown.json",
      startedAt = "2026-07-18T10:00:00Z",
      pid = 999999,
      outcome = "abandoned"
    )
    val (runs, warnings) = ManifestReader.list(workDir, Nil, alwaysDead)
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
    val (runs, _) = ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(runs.map(_.crashed), List(false))

  test(
    "list skips a manifest with an unparseable startedAt, warning by filename"
  ):
    val workDir = TempDirs.dir()
    writeManifest(workDir, "badstart.json", startedAt = "not-a-timestamp")
    val (runs, warnings) = ManifestReader.list(workDir, Nil, alwaysDead)
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
    val (runs, warnings) = ManifestReader.list(workDir, Nil, alwaysDead)
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
      ManifestReader.list(workDir, Nil, alwaysDead)
    )
    assert(ex.getMessage.contains("symlink"), ex.getMessage)

  test("list spans several worktrees, newest-first across all of them"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeManifest(checkout, "a.json", startedAt = "2026-07-18T10:00:00Z")
    writeManifest(worktree, "b.json", startedAt = "2026-07-18T12:00:00Z")
    writeManifest(checkout, "c.json", startedAt = "2026-07-18T11:00:00Z")
    val (runs, warnings) =
      ManifestReader.list(checkout, List(worktree), alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(
      runs.map(_.manifest.startedAt.toString),
      List(
        "2026-07-18T12:00:00Z",
        "2026-07-18T11:00:00Z",
        "2026-07-18T10:00:00Z"
      )
    )

  test("list merges warnings from every directory, not just the first"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    os.write(
      runsDir(checkout) / "broken-here.json",
      "not json {{{",
      createFolders = true
    )
    os.write(
      runsDir(worktree) / "broken-there.json",
      "not json {{{",
      createFolders = true
    )
    writeManifest(worktree, "good.json", startedAt = "2026-07-18T12:00:00Z")
    val (runs, warnings) =
      ManifestReader.list(checkout, List(worktree), alwaysDead)
    assertEquals(runs.size, 1)
    assert(warnings.exists(_.contains("broken-here.json")), warnings.toString)
    assert(warnings.exists(_.contains("broken-there.json")), warnings.toString)

  test("list: an unreadable worktree is one warning, not a lost listing"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeManifest(checkout, "a.json", startedAt = "2026-07-18T10:00:00Z")
    // A tree left behind by a run under another uid, or one being removed in
    // another terminal: it must not take the shell's own runs down with it.
    os.makeDir.all(runsDir(worktree))
    os.perms.set(runsDir(worktree), "---------")
    assume(
      scala.util.Try(os.list(runsDir(worktree))).isFailure,
      "needs a user that file permissions apply to"
    )
    try
      val (runs, warnings) =
        ManifestReader.list(checkout, List(worktree), alwaysDead)
      assertEquals(runs.size, 1)
      assertEquals(warnings.size, 1)
      assert(warnings.head.contains(worktree.toString), warnings.head)
    finally os.perms.set(runsDir(worktree), "rwxr-xr-x")

  test("list: a symlinked .orca in another worktree warns, it does not abort"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeManifest(checkout, "a.json", startedAt = "2026-07-18T10:00:00Z")
    val outside = TempDirs.dir() / "outside-runs"
    os.makeDir.all(outside)
    os.makeDir.all(worktree / ".orca" / "cache")
    os.symlink(runsDir(worktree), outside)
    // The hard abort stays for the caller's OWN directory (the case above);
    // refusing to read someone else's tree is the whole remedy there.
    val (runs, warnings) =
      ManifestReader.list(checkout, List(worktree), alwaysDead)
    assertEquals(runs.size, 1)
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("symlink"), warnings.head)
