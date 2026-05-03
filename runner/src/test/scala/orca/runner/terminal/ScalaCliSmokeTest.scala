package orca.runner.terminal

/** Publishes the library to the local Ivy cache, then exercises both the
  * minimal smoke script and the real flow scripts under `examples/` via
  * `scala-cli`. Gated on `ORCA_INTEGRATION` because it shells out to a real sbt
  * + scala-cli and the fan-out across examples can take several minutes cold.
  * CI sets the env var; local devs opt in when they want the check.
  */
class ScalaCliSmokeTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    20.minutes

  /** Memoise the publishLocal step across the suite's tests — every script here
    * links against the same `0.1.0-SNAPSHOT`, so re-publishing once per test
    * would just multiply the slowest step. The fixture runs once per suite,
    * fails fast if sbt fails, and feeds the resolved repo root into each test
    * that needs it.
    */
  private val publishedRepo = new munit.Fixture[os.Path]("publishedRepo"):
    private var resolved: os.Path = null
    override def apply(): os.Path = resolved
    override def beforeAll(): Unit =
      val repoRoot = findRepoRoot()
      val publishResult = os
        .proc("sbt", "publishLocal")
        .call(cwd = repoRoot, check = false)
      assertEquals(
        publishResult.exitCode,
        0,
        s"publishLocal failed: ${publishResult.err.text()}"
      )
      resolved = repoRoot

  override def munitFixtures: Seq[munit.AnyFixture[?]] = Seq(publishedRepo)

  test(
    "scala-cli runs a minimal script that links against the published library"
  ):
    val scriptDir = os.temp.dir()
    val script = scriptDir / "hello.sc"
    os.write(
      script,
      """//> using scala 3.3.6
        |//> using repository ivy2Local
        |//> using dep com.virtuslab::orca:0.1.0-SNAPSHOT
        |//> using jvm 21
        |
        |import orca.{*, given}
        |
        |flow(args = OrcaArgs("smoke test")):
        |  println(s"userPrompt=$userPrompt")
        |""".stripMargin
    )

    val runResult = os
      .proc("scala-cli", "run", script.toString)
      .call(cwd = scriptDir, check = false, mergeErrIntoOut = true)
    val runOutput = runResult.out.text()
    assertEquals(runResult.exitCode, 0, s"scala-cli run failed: $runOutput")
    assert(
      runOutput.contains("userPrompt=smoke test"),
      s"expected stdout to contain 'userPrompt=smoke test', got: $runOutput"
    )

  /** Add new examples here so they're picked up by the compile-check loop. Each
    * entry is a path under `examples/` (the seed scripts copy these `.sc` files
    * into the user's project, so they live inside `test-project/`).
    */
  private val examples: Seq[String] = Seq(
    "01-simple/test-project/implement.sc",
    "02-bugfix/test-project/bugfix.sc",
    "03-epic/test-project/epic.sc"
  )

  /** Each example flow script is a real-world consumer of the library — when a
    * public-API rename or signature change ships, these scripts are the first
    * thing that breaks for users. Compile-checking them here closes the gap
    * between sbt's internal compile (which doesn't see `examples/`) and what a
    * fresh user actually runs.
    */
  for relPath <- examples do
    test(s"example $relPath compiles via scala-cli"):
      val repoRoot = publishedRepo()
      val scriptPath = repoRoot / "examples" / os.RelPath(relPath)
      val result = os
        .proc("scala-cli", "compile", scriptPath.toString)
        .call(cwd = repoRoot, check = false, mergeErrIntoOut = true)
      assertEquals(
        result.exitCode,
        0,
        s"scala-cli compile failed for $relPath:\n${result.out.text()}"
      )

  /** Walk up from the test's working directory until we see a build.sbt. */
  private def findRepoRoot(): os.Path =
    @scala.annotation.tailrec
    def loop(p: os.Path): os.Path =
      if os.exists(p / "build.sbt") then p
      else if p == p / os.up then
        throw new RuntimeException(
          "No build.sbt found walking up from " + os.pwd
        )
      else loop(p / os.up)
    loop(os.pwd)
