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
    s"""package orca.fixture
       |import language.experimental.safe
       |$body
       |""".stripMargin

  /** What each fixture reaches, the symbol its error must name, and the code.
    */
  private val unreachable: List[(String, String, String)] = List(
    (
      "an effect implementation",
      "QuietProc",
      "def f(cwd: os.Path): Int = orca.subprocess.QuietProc.call(Seq(\"true\"), cwd).exitCode"
    ),
    (
      "the process scan behind a spawned process",
      "CookieScan",
      "def f(c: orca.subprocess.EnvCookie): Int = orca.subprocess.CookieScan.carrying(c).size"
    ),
    ("os-lib directly", "read", "def f(p: os.Path): String = os.read(p)"),
    (
      "FileSystem.ambient",
      "ambient",
      "def f(): orca.effect.FileSystem = orca.effect.FileSystem.ambient"
    ),
    (
      "Processes.ambient",
      "ambient",
      "def f(): orca.effect.Processes = orca.effect.Processes.ambient"
    ),
    (
      "Network.ambient",
      "ambient",
      "def f(): orca.effect.Network = orca.effect.Network.ambient"
    ),
    (
      "an ambient effect passed through an inline facade",
      "read",
      "def f(p: os.Path): String = orca.safe.concurrency.supervised(os.read(p))"
    )
  )

  unreachable.foreach: (what, symbol, code) =>
    test(s"NEGATIVE: safe code cannot reference $what"):
      val errors = Dotc.errorsOf(safeFixture(code))
      assert(
        errors.exists(e => e.contains("from safe code") && e.contains(symbol)),
        s"expected a safe-mode rejection naming $symbol, got: $errors"
      )

  test("NEGATIVE: safe code cannot reference the HTTP client implementation"):
    // In its own package: it is `private[effect]`, and must fail for being
    // unsafe, not for being inaccessible.
    val errors = Dotc.errorsOf(
      """package orca.effect
        |import language.experimental.safe
        |def f(): HttpClient = JdkHttpClient("http://127.0.0.1:1", "u", "p")
        |""".stripMargin
    )
    assert(
      errors.exists(e =>
        e.contains("from safe code") && e.contains("JdkHttpClient")
      ),
      s"expected a safe-mode rejection naming JdkHttpClient, got: $errors"
    )

  test("POSITIVE: safe code uses effects through capabilities"):
    val errors = Dotc.errorsOf(
      safeFixture(
        """import orca.effect.{FileSystem, Network, Processes}
          |import orca.safe.concurrency.supervised
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
          |import orca.safe.concurrency.*
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
