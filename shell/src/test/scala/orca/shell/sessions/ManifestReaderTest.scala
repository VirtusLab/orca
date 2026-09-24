package orca.shell.sessions

import orca.{AttemptId, OrcaDir, OrcaFlowException}
import orca.runner.manifest.AttemptManifest
import orca.testkit.TempDirs

import java.time.Instant

class ManifestReaderTest extends munit.FunSuite:

  private val alwaysDead: AttemptManifest => Boolean = _ => false
  private val alwaysAlive: AttemptManifest => Boolean = _ => true

  private def attemptsDir(workDir: os.Path): os.Path =
    workDir / ".orca" / "cache" / "attempts"

  /** One recorded session, so the manifest is one the listing offers. */
  private val oneSession =
    """{"backend": "ClaudeCode", "wireId": "w", "agent": "claude", "role": null,
      |"stage": null, "lastActiveAt": "2026-07-18T10:00:00Z"}""".stripMargin

  /** The file name of the manifest of the attempt `startedAt` and `pid` spell.
    */
  private def manifestName(startedAt: String, pid: Long = 111): String =
    s"${AttemptId(Instant.parse(startedAt), pid).value}${OrcaDir.ManifestSuffix}"

  private def writeManifest(
      workDir: os.Path,
      startedAt: String,
      pid: Long = 111,
      status: String = "Succeeded",
      sessions: String = oneSession
  ): Unit =
    val name = manifestName(startedAt, pid)
    val json =
      s"""{
         |  "orcaVersion": "0.0.test",
         |  "workDir": "${workDir.toString}",
         |  "pid": $pid,
         |  "startedAt": "$startedAt",
         |  "status": "$status",
         |  "sessions": [$sessions]
         |}""".stripMargin
    os.write(attemptsDir(workDir) / name, json, createFolders = true)

  test(
    "list returns an empty listing for an absent attempts dir, creating nothing"
  ):
    val workDir = TempDirs.dir()
    assertEquals(
      ManifestReader.list(workDir, Nil, alwaysDead),
      AttemptListing(Nil, Nil)
    )
    assert(!os.exists(workDir / ".orca"), "reading must not create .orca")

  test("list returns an empty listing for an empty attempts dir"):
    val workDir = TempDirs.dir()
    os.makeDir.all(attemptsDir(workDir))
    assertEquals(
      ManifestReader.list(workDir, Nil, alwaysDead),
      AttemptListing(Nil, Nil)
    )

  test("list orders manifests newest-first by startedAt"):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      startedAt = "2026-07-18T10:00:00Z"
    )
    writeManifest(
      workDir,
      startedAt = "2026-07-18T12:00:00Z"
    )
    writeManifest(
      workDir,
      startedAt = "2026-07-18T11:00:00Z"
    )
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(
      attempts.map(_.manifest.startedAt),
      List(
        Instant.parse("2026-07-18T12:00:00Z"),
        Instant.parse("2026-07-18T11:00:00Z"),
        Instant.parse("2026-07-18T10:00:00Z")
      )
    )

  test("a bad manifest gives one warning naming the file"):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      startedAt = "2026-07-18T11:00:00Z"
    )
    os.write(
      attemptsDir(workDir) / manifestName("2026-07-18T10:00:00Z"),
      s"""{
        |  "orcaVersion": "0.0.test",
        |  "pid": 111,
        |  "startedAt": "2026-07-18T10:00:00Z",
        |  "status": "Succeeded",
        |  "sessions": [$oneSession]
        |}""".stripMargin,
      createFolders = true
    )
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(
      attempts.map(_.manifest.startedAt),
      List(Instant.parse("2026-07-18T11:00:00Z"))
    )
    assertEquals(warnings.size, 1)
    assert(
      warnings.head.contains(manifestName("2026-07-18T10:00:00Z")),
      s"expected the filename in the warning, got: ${warnings.head}"
    )

  test("a manifest not named after an attempt id is skipped with a warning"):
    val workDir = TempDirs.dir()
    writeManifest(workDir, startedAt = "2026-07-18T10:00:00Z")
    os.move(
      attemptsDir(workDir) / manifestName("2026-07-18T10:00:00Z"),
      attemptsDir(workDir) / "copy.manifest.json"
    )
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(attempts, Nil)
    assertEquals(
      warnings,
      List(
        s"skipping ${attemptsDir(workDir) / "copy.manifest.json"}: not named after an attempt id"
      )
    )

  test("list takes the attempt id from the file name, not the manifest body"):
    val workDir = TempDirs.dir()
    writeManifest(workDir, startedAt = "2026-07-18T10:00:00Z")
    val renamed = manifestName("2026-07-18T11:00:00Z", pid = 7)
    os.move(
      attemptsDir(workDir) / manifestName("2026-07-18T10:00:00Z"),
      attemptsDir(workDir) / renamed
    )
    val AttemptListing(attempts, _) =
      ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(
      attempts.map(_.id),
      List(AttemptId(Instant.parse("2026-07-18T11:00:00Z"), 7))
    )

  test("list ignores a file that is not a manifest by suffix"):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      startedAt = "2026-07-18T10:00:00Z"
    )
    os.write(attemptsDir(workDir) / "a.cost.jsonl", "not json {{{")
    os.write(attemptsDir(workDir) / "b.json", "not json {{{")
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(attempts.size, 1)

  test("list drops a manifest that records no session"):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      startedAt = "2026-07-18T10:00:00Z"
    )
    writeManifest(
      workDir,
      startedAt = "2026-07-18T12:00:00Z",
      sessions = ""
    )
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(
      attempts.map(_.manifest.startedAt),
      List(Instant.parse("2026-07-18T10:00:00Z"))
    )

  test("a running manifest with a dead pid is included and marked crashed"):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      startedAt = "2026-07-18T10:00:00Z",
      pid = 999999,
      status = "Running"
    )
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(attempts.map(_.observedStatus), List(ObservedStatus.Crashed))

  test("a running manifest with a live pid is included and running"):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      startedAt = "2026-07-18T10:00:00Z",
      pid = 1,
      status = "Running"
    )
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(workDir, Nil, alwaysAlive)
    assertEquals(warnings, Nil)
    assertEquals(attempts.map(_.observedStatus), List(ObservedStatus.Running))

  test(
    "list skips a manifest with an unrecognised outcome, warning by filename"
  ):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      startedAt = "2026-07-18T10:00:00Z",
      status = "abandoned"
    )
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(attempts, Nil)
    assertEquals(warnings.size, 1)
    assert(
      warnings.head.contains(manifestName("2026-07-18T10:00:00Z")),
      warnings.head
    )

  test(
    "list skips a manifest with an unrecognised backend, warning by filename"
  ):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      startedAt = "2026-07-18T10:00:00Z",
      sessions = oneSession.replace("ClaudeCode", "claude")
    )
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(attempts, Nil)
    assertEquals(warnings.size, 1)
    assert(
      warnings.head.contains(manifestName("2026-07-18T10:00:00Z")),
      warnings.head
    )

  test("a succeeded manifest with a dead pid is not crashed"):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      startedAt = "2026-07-18T10:00:00Z",
      pid = 999999,
      status = "Succeeded"
    )
    val AttemptListing(attempts, _) =
      ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(attempts.map(_.observedStatus), List(ObservedStatus.Succeeded))

  test(
    "list skips a manifest whose minted key has no stage, warning by filename"
  ):
    val workDir = TempDirs.dir()
    writeManifest(
      workDir,
      startedAt = "2026-07-18T10:00:00Z",
      sessions =
        """{"backend": "ClaudeCode", "wireId": "w", "agent": "claude", "role": null,
          |"stage": null, "minted": {"name": "coder"},
          |"lastActiveAt": "2026-07-18T10:00:00Z"}""".stripMargin
    )
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(workDir, Nil, alwaysDead)
    assertEquals(attempts, Nil)
    assertEquals(warnings.size, 1)
    assert(
      warnings.head.contains(manifestName("2026-07-18T10:00:00Z")),
      s"expected the filename in the warning, got: ${warnings.head}"
    )

  test("list aborts on a symlinked .orca/cache/attempts"):
    val workDir = TempDirs.dir()
    val outside = TempDirs.dir() / "outside-attempts"
    os.makeDir.all(outside)
    os.makeDir.all(workDir / ".orca" / "cache")
    os.symlink(attemptsDir(workDir), outside)
    val ex = intercept[OrcaFlowException](
      ManifestReader.list(workDir, Nil, alwaysDead)
    )
    assert(ex.getMessage.contains("symlink"), ex.getMessage)

  test("list spans several worktrees, newest-first across all of them"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeManifest(
      checkout,
      startedAt = "2026-07-18T10:00:00Z"
    )
    writeManifest(
      worktree,
      startedAt = "2026-07-18T12:00:00Z"
    )
    writeManifest(
      checkout,
      startedAt = "2026-07-18T11:00:00Z"
    )
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(checkout, List(worktree), alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(
      attempts.map(_.manifest.startedAt.toString),
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
      attemptsDir(checkout) / manifestName("2026-07-18T10:00:00Z"),
      "not json {{{",
      createFolders = true
    )
    os.write(
      attemptsDir(worktree) / manifestName("2026-07-18T11:00:00Z"),
      "not json {{{",
      createFolders = true
    )
    writeManifest(
      worktree,
      startedAt = "2026-07-18T12:00:00Z"
    )
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(checkout, List(worktree), alwaysDead)
    assertEquals(attempts.size, 1)
    assert(
      warnings.exists(_.contains(manifestName("2026-07-18T10:00:00Z"))),
      warnings.toString
    )
    assert(
      warnings.exists(_.contains(manifestName("2026-07-18T11:00:00Z"))),
      warnings.toString
    )

  test("list: an unreadable worktree is one warning, not a lost listing"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeManifest(
      checkout,
      startedAt = "2026-07-18T10:00:00Z"
    )
    // A tree left behind by a run under another uid, or one being removed in
    // another terminal: it must not take the shell's own attempts down with
    // it.
    os.makeDir.all(attemptsDir(worktree))
    os.perms.set(attemptsDir(worktree), "---------")
    assume(
      scala.util.Try(os.list(attemptsDir(worktree))).isFailure,
      "needs a user that file permissions apply to"
    )
    try
      val AttemptListing(attempts, warnings) =
        ManifestReader.list(checkout, List(worktree), alwaysDead)
      assertEquals(attempts.size, 1)
      assertEquals(warnings.size, 1)
      assert(warnings.head.contains(worktree.toString), warnings.head)
    finally os.perms.set(attemptsDir(worktree), "rwxr-xr-x")

  test("list: a symlinked .orca in another worktree warns, it does not abort"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeManifest(
      checkout,
      startedAt = "2026-07-18T10:00:00Z"
    )
    val outside = TempDirs.dir() / "outside-attempts"
    os.makeDir.all(outside)
    os.makeDir.all(worktree / ".orca" / "cache")
    os.symlink(attemptsDir(worktree), outside)
    // The hard abort stays for the caller's OWN directory (the case above);
    // refusing to read someone else's tree is the whole remedy there.
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(checkout, List(worktree), alwaysDead)
    assertEquals(attempts.size, 1)
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("symlink"), warnings.head)

  test("processAlive: this process, for an attempt it started, is alive"):
    val attempt = ManifestFixtures.manifest(
      pid = ProcessHandle.current().pid(),
      startedAt = Instant.now().toString,
      sessions = Nil
    )
    assert(ManifestReader.processAlive(attempt))

  test("processAlive: a live process started after the attempt reused its pid"):
    val attempt = ManifestFixtures.manifest(
      pid = ProcessHandle.current().pid(),
      startedAt = Instant.EPOCH.toString,
      sessions = Nil
    )
    assert(!ManifestReader.processAlive(attempt))
