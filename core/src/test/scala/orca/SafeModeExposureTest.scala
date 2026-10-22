package orca

import orca.testkit.Dotc

/** What safe-mode code may reach: capabilities and the `@assumeSafe` facades,
  * but neither an effect implementation nor the mint of a capability.
  *
  * Fixtures sit in `package orca.fixture`, so a `private[orca]` symbol fails
  * for being unsafe, not for being inaccessible.
  */
class SafeModeExposureTest extends munit.FunSuite:

  private def safeFixture(body: String): String =
    fixtureIn("orca.fixture", body)

  private def fixtureIn(pkg: String, body: String): String =
    s"""package $pkg
       |import language.experimental.safe
       |$body
       |""".stripMargin

  /** What each fixture reaches, the symbol its error must name, the package it
    * sits in, and the code. A `private[effect]` symbol is reached from
    * `orca.effect`, so it too fails for being unsafe.
    */
  private val unreachable: List[(String, String, String, String)] = List(
    (
      "an effect implementation",
      "QuietProc",
      "orca.fixture",
      "def f(cwd: os.Path): Int = orca.subprocess.QuietProc.call(Seq(\"true\"), cwd).exitCode"
    ),
    (
      "the piped subprocess implementation",
      "OsPipedSubProcess",
      "orca.fixture",
      "def f(cwd: os.Path): orca.subprocess.PipedCliProcess = orca.subprocess.OsPipedSubProcess.spawn(Seq(\"true\"), Map.empty, cwd)"
    ),
    (
      "the process scan behind a spawned process",
      "CookieScan",
      "orca.fixture",
      "def f(c: orca.subprocess.EnvCookie): Int = orca.subprocess.CookieScan.carrying(c).size"
    ),
    (
      "the HTTP client implementation",
      "JdkHttpClient",
      "orca.effect",
      "def f(): HttpClient = JdkHttpClient(\"http://127.0.0.1:1\", \"u\", \"p\")"
    ),
    (
      "the local HTTP server implementation",
      "LocalServer",
      "orca.effect",
      "def f()(using ox.Ox): Int = LocalServer.serve(Nil, scala.concurrent.duration.Duration(1, \"s\"))"
    ),
    (
      "os-lib directly",
      "read",
      "orca.fixture",
      "def f(p: os.Path): String = os.read(p)"
    ),
    (
      "FileSystem.ambient",
      "ambient",
      "orca.fixture",
      "def f(): orca.effect.FileSystem = orca.effect.FileSystem.ambient"
    ),
    (
      "Processes.ambient",
      "ambient",
      "orca.fixture",
      "def f(): orca.effect.Processes = orca.effect.Processes.ambient"
    ),
    (
      "Network.ambient",
      "ambient",
      "orca.fixture",
      "def f(): orca.effect.Network = orca.effect.Network.ambient"
    ),
    (
      "an ambient effect passed through an inline facade",
      "read",
      "orca.fixture",
      "def f(p: os.Path): String = orca.concurrency.supervised(os.read(p))"
    )
  )

  unreachable.foreach: (what, symbol, pkg, code) =>
    test(s"NEGATIVE: safe code cannot reference $what"):
      val errors = Dotc.errorsOf(fixtureIn(pkg, code))
      assert(
        errors.exists(e => e.contains("from safe code") && e.contains(symbol)),
        s"expected a safe-mode rejection naming $symbol, got: $errors"
      )

  test("NEGATIVE: a Ref update cannot capture a capability"):
    val errors = Dotc.errorsOf(
      safeFixture(
        """import orca.concurrency.Ref
          |import orca.effect.FileSystem
          |def f(r: Ref[Int], p: os.Path)(using fs: FileSystem): Unit =
          |  r.update(n => if fs.isFile(p) then n + 1 else n)
          |""".stripMargin
      )
    )
    assert(
      errors.exists(
        _.contains("capability `fs` cannot flow into capture set {}")
      ),
      s"expected the capture of fs rejected, got: $errors"
    )

  test("POSITIVE: safe code uses effects through capabilities"):
    val errors = Dotc.errorsOf(
      safeFixture(
        """import orca.effect.{FileSystem, Network, Processes}
          |import orca.concurrency.supervised
          |import orca.safe.prelude.{*, given}
          |def head(cwd: os.Path)(using procs: Processes): String =
          |  procs.call(Seq("git", "rev-parse", "HEAD"), cwd).stdout
          |def log(cwd: os.Path)(using procs: Processes): String =
          |  procs.callCapped(Seq("git", "log"), 1024, cwd).out
          |def firstLine(cwd: os.Path)(using procs: Processes): Option[String] =
          |  val p = procs.spawnPiped(Seq("echo", "hi"), cwd)
          |  try p.stdoutLines.nextOption()
          |  finally p.destroyForciblyTree()
          |def size(p: os.Path)(using fs: FileSystem): Int =
          |  if fs.isFile(p) then fs.read(p).length else 0
          |def rewrite(dir: os.Path)(using fs: FileSystem): List[os.Path] =
          |  fs.writeOver(dir / "a.txt", "x")
          |  fs.files(dir, _ => true).map(fs.realPath)
          |def serve()(using net: Network): Int = supervised:
          |  net.serveLocal(Nil, 1.second)
          |def status(base: String)(using net: Network): Int = supervised:
          |  net.httpClient(base, "u", "p").get("/", 1.second).status
          |""".stripMargin
      )
    )
    assert(errors.isEmpty, s"expected no errors, got: $errors")

  test("POSITIVE: safe code uses the facades"):
    val errors = Dotc.errorsOf(
      safeFixture(
        """import orca.json.{JsonCodec, JsonData, given}
          |import orca.safe.Logger
          |import orca.concurrency.*
          |import orca.safe.prelude.{*, given}
          |
          |case class Wire(n: Int, tags: List[String]) derives JsonCodec
          |enum Shape:
          |  case Dot
          |  case Line(length: Int)
          |given JsonCodec[Shape] =
          |  JsonCodec.derived(using JsonCodec.config.withDiscriminatorFieldName(None))
          |
          |case class Inner(name: String) derives JsonData
          |case class Outer(inner: Inner, kind: Kind) derives JsonData
          |enum Kind derives JsonData:
          |  case A, B
          |enum Event derives JsonData:
          |  case Start
          |  case Stop(code: Int)
          |
          |private val log = Logger("fixture")
          |
          |def child(base: os.Path): os.Path = base / "a" / up / "b"
          |def parsed(s: String): Either[String, Int] =
          |  either:
          |    val n = s.toIntOption.toRight("not a number").ok()
          |    n.pipe(_ + 1).tap(v => log.debug(s"parsed $v"))
          |def guarded(f: () -> Int): Int =
          |  try f()
          |  catch case NonFatal(_) => 0
          |def concurrent(): Int = supervised:
          |  val a = fork(1)
          |  forkDiscard(sleep(1.millis))
          |  a.join()
          |def caught(s: String): Either[Throwable, Int] = s.toInt.catching[Throwable]
          |def poll(): Nothing = forever(sleep(1.millis))
          |""".stripMargin
      )
    )
    assert(errors.isEmpty, s"expected no errors, got: $errors")
