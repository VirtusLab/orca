package orca.backend.cursor

import orca.subprocess.{CliResult, StubCliRunner}
import orca.testkit.TempDirs
import ox.discard

class CursorSessionStoreTest extends munit.FunSuite:
  private val id = "6f0f1234-5678-4abc-9def-000000000001"

  // SQLite fixtures contain only meta('0', '{}') and a synthetic one-byte blob,
  // or the same empty schema. Their immutable query results are 1 and 0.
  // CLI replies are injected so unit-test hosts need no sqlite3 installation.
  private def store(workDir: os.Path, root: os.Path, fixture: String): os.Path =
    val path = CursorSessionStore.database(workDir, id, root).get
    val stream =
      getClass.getResourceAsStream(s"/orca/backend/cursor/$fixture.db")
    try os.write(path, stream.readAllBytes(), createFolders = true)
    finally stream.close()
    path

  private def populated: StubCliRunner = new StubCliRunner(
    CliResult(0, "1\n", "")
  )

  test("checkout key is lowercase MD5 of the canonical absolute UTF-8 path"):
    val root = TempDirs.dir()
    // Independent MD5 vector for a canonical directory that exists on every host.
    assertEquals(
      CursorSessionStore.database(os.root, id, root),
      Some(root / "6666cd76f96956469e7be39d750cc7d9" / id / "store.db")
    )

  test("symlinked checkout resolves to the physical checkout history"):
    val root = TempDirs.dir()
    val workDir = root / "checkout"
    os.makeDir(workDir)
    val alias = root / "alias"
    os.symlink(alias, workDir)
    assertEquals(
      CursorSessionStore.database(alias, id, root),
      CursorSessionStore.database(workDir, id, root)
    )

  test("macOS var alias resolves to private var"):
    if System.getProperty("os.name").startsWith("Mac") then
      val workDir = TempDirs.dir()
      val real = workDir.toNIO.toRealPath().toString
      assert(real.startsWith("/private/var/"), real)
      val alias = os.Path(real.stripPrefix("/private"))
      assertEquals(
        CursorSessionStore.database(alias, id, workDir),
        CursorSessionStore.database(workDir, id, workDir)
      )

  test(
    "populated database without a transcript uses the fixed read-only scalar query"
  ):
    val root = TempDirs.dir() / "chats #?% ü"
    val workDir = TempDirs.dir()
    val path = store(workDir, root, "populated")
    val cli = populated
    assert(CursorSessionStore.resumable(workDir, id, root, cli))
    assertEquals(os.list(path / os.up).map(_.last), Seq("store.db"))
    val call = cli.calls.head
    assertEquals(call.cwd, workDir)
    assertEquals(call.stdin, "")
    assertEquals(
      call.args.take(6),
      List("sqlite3", "-batch", "-noheader", "-readonly", "-init", "/dev/null")
    )
    assertEquals(
      call.args.last,
      "SELECT EXISTS (SELECT 1 FROM meta WHERE key = '0') AND EXISTS (SELECT 1 FROM blobs);"
    )
    val uri = java.net.URI.create(call.args(6))
    assertEquals(uri.getScheme, "file")
    assertEquals(uri.getPath, path.toString)
    assertEquals(uri.getRawQuery, "mode=ro&immutable=1")
    assertEquals(uri.getRawFragment, null)
    assert(call.args(6).forall(_ <= 127))
    assertEquals(cli.calls.size, 1)

  test("stale JSONL mirror without a database cannot verify history"):
    val root = TempDirs.dir()
    val workDir = TempDirs.dir()
    val slug =
      workDir.toString.stripPrefix("/").replaceAll("[^a-zA-Z0-9]+", "-")
    os.write(
      root / "projects" / slug / "agent-transcripts" / id / s"$id.jsonl",
      "{}\n",
      createFolders = true
    )
    val cli = populated
    assert(!CursorSessionStore.resumable(workDir, id, root / "chats", cli))
    assertEquals(cli.calls, Nil)

  test("initialized empty SQLite store is not resumable"):
    val root = TempDirs.dir()
    val workDir = TempDirs.dir()
    val _ = store(workDir, root, "empty")
    val cli = new StubCliRunner(CliResult(0, "0\n", ""))
    assert(!CursorSessionStore.resumable(workDir, id, root, cli))
    assertEquals(cli.calls.size, 1)

  test("only a successful scalar one verifies history"):
    val root = TempDirs.dir()
    val workDir = TempDirs.dir()
    val _ = store(workDir, root, "populated")
    val cli = populated
    for result <- List(
        // Query failures include corrupt or unsupported databases.
        CliResult(1, "1\n", "failed"),
        CliResult(0, "", ""),
        CliResult(0, "11\n", ""),
        CliResult(0, "1\n1\n", ""),
        CliResult(0, " 1\n", "")
      )
    do
      cli.setResponse(result)
      assert(
        !CursorSessionStore.resumable(workDir, id, root, cli),
        result.toString
      )

  test("missing sqlite loader fails closed"):
    val root = TempDirs.dir()
    val workDir = TempDirs.dir()
    val _ = store(workDir, root, "populated")
    val cli = new StubCliRunner():
      override def run(
          args: Seq[String],
          stdin: String,
          env: Map[String, String],
          cwd: os.Path
      ): CliResult =
        throw new java.io.IOException("sqlite3 unavailable")
    assert(!CursorSessionStore.resumable(workDir, id, root, cli))

  test("a directory at the database path is unverifiable"):
    val root = TempDirs.dir()
    val workDir = TempDirs.dir()
    os.makeDir.all(CursorSessionStore.database(workDir, id, root).get)
    val cli = populated
    assert(!CursorSessionStore.resumable(workDir, id, root, cli))
    assertEquals(cli.calls, Nil)

  test("unreadable database is refused without invoking sqlite"):
    val root = TempDirs.dir()
    val workDir = TempDirs.dir()
    val path = store(workDir, root, "populated")
    val cli = populated
    val perms = java.nio.file.Files.getPosixFilePermissions(path.toNIO)
    try
      java.nio.file.Files
        .setPosixFilePermissions(path.toNIO, java.util.Set.of())
        .discard
      assert(!CursorSessionStore.resumable(workDir, id, root, cli))
      assertEquals(cli.calls, Nil)
    finally
      java.nio.file.Files.setPosixFilePermissions(path.toNIO, perms).discard

  test("history from another checkout does not verify the recorded session"):
    val root = TempDirs.dir()
    val _ = store(TempDirs.dir(), root, "populated")
    val cli = populated
    assert(!CursorSessionStore.resumable(TempDirs.dir(), id, root, cli))
    assertEquals(cli.calls, Nil)

  test("invalid ids never resolve a store or invoke sqlite"):
    val root = TempDirs.dir()
    val cli = populated
    for invalid <- List("", "../escape", "-flag", "id", id + "/x", id + "\n") do
      assertEquals(CursorSessionStore.database(root, invalid, root), None)
      assert(!CursorSessionStore.resumable(root, invalid, root, cli))
    assertEquals(cli.calls, Nil)

  test("missing checkout cannot verify history"):
    val root = TempDirs.dir()
    val cli = populated
    assert(!CursorSessionStore.resumable(root / "gone", id, root, cli))
    assertEquals(cli.calls, Nil)
