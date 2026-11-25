package orca.backend.cursor

import orca.subprocess.{
  CliResult,
  FakePipedCliProcess,
  OsProcCliRunner,
  PipedCliProcess,
  SpawnStubCliRunner,
  StubCliRunner
}
import orca.testkit.ProcessProbe.{alive, awaitDead}
import orca.testkit.TempDirs
import ox.{discard, forkCancellable, supervised, timeout}

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*

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

  private def reply(result: CliResult): FakePipedCliProcess =
    val process = new FakePipedCliProcess(initiallyAlive = false):
      override def waitForExit(): Int = result.exitCode
    result.stdout.linesIterator.foreach(process.enqueueStdout)
    result.stderr.linesIterator.foreach(process.enqueueStderr)
    process.closeStdout()
    process.closeStderr()
    process

  private def populated: SpawnStubCliRunner =
    new SpawnStubCliRunner(List(reply(CliResult(0, "1\n", ""))))

  test("CURSOR_CONFIG_DIR takes precedence over XDG_CONFIG_HOME"):
    val workDir = TempDirs.dir()
    val profile = workDir / "cursor profile"
    val env = Map(
      "CURSOR_CONFIG_DIR" -> profile.toString,
      "XDG_CONFIG_HOME" -> (workDir / "xdg").toString
    )
    assertEquals(
      CursorSessionStore.configuredChatsRoot(workDir, env.get),
      profile / "chats"
    )

  test(
    "XDG_CONFIG_HOME selects its cursor subdirectory for blank Cursor config"
  ):
    val workDir = TempDirs.dir()
    val xdg = workDir / "xdg"
    val env =
      Map("CURSOR_CONFIG_DIR" -> " \t", "XDG_CONFIG_HOME" -> xdg.toString)
    assertEquals(
      CursorSessionStore.configuredChatsRoot(workDir, env.get),
      xdg / "cursor" / "chats"
    )

  test("without profile overrides Cursor uses the home config directory"):
    val home = TempDirs.dir()
    assertEquals(
      CursorSessionStore.configuredChatsRoot(os.root, _ => None, home),
      home / ".cursor" / "chats"
    )

  test("a relative Cursor profile path resolves from the physical checkout"):
    val root = TempDirs.dir()
    val workDir = root / "physical" / "checkout"
    os.makeDir.all(workDir)
    val alias = root / "alias"
    os.symlink(alias, workDir)
    val env = Map("CURSOR_CONFIG_DIR" -> "../profile ")
    val checkout = os.Path(workDir.toNIO.toRealPath())
    assertEquals(
      CursorSessionStore.configuredChatsRoot(alias, env.get),
      checkout / os.up / "profile " / "chats"
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
    val call = cli.spawnCalls.head
    assertEquals(call.cwd, workDir)
    assertEquals(call.env, Map.empty[String, String])
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
    val cli = new SpawnStubCliRunner(List(reply(CliResult(0, "0\n", ""))))
    assert(!CursorSessionStore.resumable(workDir, id, root, cli))
    assertEquals(cli.calls.size, 1)

  test("only a successful scalar one verifies history"):
    val root = TempDirs.dir()
    val workDir = TempDirs.dir()
    val _ = store(workDir, root, "populated")
    for result <- List(
        // Query failures include corrupt or unsupported databases.
        CliResult(1, "1\n", "failed"),
        CliResult(0, "", ""),
        CliResult(0, "11\n", ""),
        CliResult(0, "1\n1\n", ""),
        CliResult(0, " 1\n", ""),
        CliResult(0, "1 \n", ""),
        CliResult(0, "\n1\n", ""),
        CliResult(0, "1\n\n", ""),
        CliResult(0, "1\r\n\r\n", "")
      )
    do
      val cli = new SpawnStubCliRunner(List(reply(result)))
      assert(
        !CursorSessionStore.resumable(workDir, id, root, cli),
        result.toString
      )

  test("missing sqlite loader fails closed"):
    val root = TempDirs.dir()
    val workDir = TempDirs.dir()
    val _ = store(workDir, root, "populated")
    val cli = new StubCliRunner():
      override def spawnPiped(
          args: Seq[String],
          env: Map[String, String],
          cwd: os.Path
      ): PipedCliProcess =
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

  private class WaitingProcess extends FakePipedCliProcess():
    val readersStarted: CountDownLatch = new CountDownLatch(2)
    val readersFinished: CountDownLatch = new CountDownLatch(2)
    val killed: CountDownLatch = new CountDownLatch(1)

    override def waitForExit(): Int =
      killed.await()
      0

    override def destroyForcibly(): Unit =
      super.destroyForcibly()
      killed.countDown()

    override def stdoutLines: Iterator[String] = tracked(super.stdoutLines)
    override def stderrLines: Iterator[String] = tracked(super.stderrLines)

    private def tracked(lines: Iterator[String]): Iterator[String] =
      new Iterator[String]:
        def hasNext: Boolean =
          readersStarted.countDown()
          try
            val more = lines.hasNext
            if !more then readersFinished.countDown()
            more
          catch
            case e: Throwable =>
              readersFinished.countDown()
              throw e
        def next(): String = lines.next()

  private def probe(
      process: FakePipedCliProcess,
      limit: FiniteDuration
  ): Boolean =
    val workDir = TempDirs.dir()
    val root = TempDirs.dir()
    val _ = store(workDir, root, "populated")
    CursorSessionStore.resumable(
      workDir,
      id,
      root,
      new SpawnStubCliRunner(List(process)),
      timeout = limit
    )

  test("a silent probe reaches its deadline and completes cleanup"):
    timeout(10.seconds):
      val process = new WaitingProcess()
      assert(!probe(process, 500.millis))
      assert(process.isStdinClosed)
      assert(!process.isAlive)
      assertEquals(process.killed.getCount, 0L)
      assertEquals(process.readersStarted.getCount, 0L)
      assertEquals(process.readersFinished.getCount, 0L)

  test("parent cancellation propagates after killing and joining the probe"):
    timeout(10.seconds):
      supervised:
        val process = new WaitingProcess()
        val running = forkCancellable(probe(process, 1.minute))
        assert(process.readersStarted.await(5, TimeUnit.SECONDS))
        val result = running.cancel()
        assert(
          result.left.toOption.exists(_.isInstanceOf[InterruptedException])
        )
        assert(process.isStdinClosed)
        assert(!process.isAlive)
        assertEquals(process.readersFinished.getCount, 0L)

  test("the probe closes stdin and drains both streams before returning"):
    timeout(10.seconds):
      val process = new WaitingProcess():
        override def waitForExit(): Int =
          require(isStdinClosed)
          require(readersStarted.await(5, TimeUnit.SECONDS))
          enqueueStdout("1")
          enqueueStderr("diagnostic")
          closeStdout()
          closeStderr()
          0
      assert(probe(process, 5.seconds))
      assertEquals(process.readersFinished.getCount, 0L)
      assert(!process.isAlive)

  test("stdin close failure still kills the acquired probe"):
    val process = new WaitingProcess():
      override def closeStdin(): Unit = throw new java.io.IOException("closed")
    timeout(10.seconds):
      assert(!probe(process, 5.seconds))
    assert(!process.isAlive)

  test("a read failure fails closed and joins the other drain"):
    timeout(10.seconds):
      val process = new WaitingProcess():
        override def stdoutLines: Iterator[String] =
          readersStarted.countDown()
          try
            require(readersStarted.await(5, TimeUnit.SECONDS))
            throw new java.io.IOException("read failed")
          finally readersFinished.countDown()
      assert(!probe(process, 1.minute))
      assertEquals(process.readersStarted.getCount, 0L)
      assertEquals(process.readersFinished.getCount, 0L)
      assert(!process.isAlive)

  test("a local silent executable is killed at the probe deadline"):
    timeout(10.seconds):
      val root = TempDirs.dir()
      val workDir = TempDirs.dir()
      val _ = store(workDir, root, "populated")
      val pidFile = root / "pid"
      val stdinClosed = root / "stdin-closed"
      val script = root / "sqlite-stub"
      os.write(
        script,
        """#!/bin/sh
if IFS= read -r line; then exit 7; fi
echo closed > "$STDIN_CLOSED"
echo $$ > "$PIDFILE"
exec sleep 30
"""
      )
      os.perms.set(script, "rwx------")
      val cli = new StubCliRunner():
        override def spawnPiped(
            args: Seq[String],
            env: Map[String, String],
            cwd: os.Path
        ): PipedCliProcess =
          OsProcCliRunner.spawnPiped(
            script.toString +: args.tail,
            env = env ++ Map(
              "PIDFILE" -> pidFile.toString,
              "STDIN_CLOSED" -> stdinClosed.toString
            ),
            cwd = cwd
          )
      assert(
        !CursorSessionStore.resumable(
          workDir,
          id,
          root,
          cli,
          timeout = 2.seconds
        )
      )
      assert(os.exists(stdinClosed))
      val pid = os.read(pidFile).trim.toLong
      assert(awaitDead(pid))
      assert(!alive(pid))
