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
    * links against the same dynver-computed version, so re-publishing once per
    * test would just multiply the slowest step. The fixture runs once per
    * suite, fails fast if sbt fails, and exposes both the repo root and the
    * published version (read back from sbt because dynver derives it from git
    * state rather than a static literal).
    */
  case class Published(repoRoot: os.Path, version: String)

  private val publishedRepo = new munit.Fixture[Published]("publishedRepo"):
    private var resolved: Published = null
    override def apply(): Published = resolved
    override def beforeAll(): Unit =
      val repoRoot = findRepoRoot()
      // `sbt --client` reuses a running sbt server when one exists, so the
      // second invocation (print version) skips the JVM cold-start.
      val publishResult = os
        .proc("sbt", "--client", "publishLocal")
        .call(cwd = repoRoot, check = false)
      assertEquals(
        publishResult.exitCode,
        0,
        s"publishLocal failed: ${publishResult.err.text()}"
      )
      val versionResult = os
        .proc("sbt", "--client", "--error", "print version")
        .call(cwd = repoRoot, check = false)
      assertEquals(
        versionResult.exitCode,
        0,
        s"reading version failed: ${versionResult.err.text()}"
      )
      // `print version` aggregates across every subproject; pick the first
      // version line (starts with whitespace + a digit) — they're all the
      // same dynver value. `sbt --client` decorates output with ANSI escapes,
      // so strip them before matching.
      val ansiEscape = "\u001b\\[[0-9;]*[A-Za-z]".r
      val version = versionResult.out
        .lines()
        .iterator
        .map(line => ansiEscape.replaceAllIn(line, "").trim)
        .find(s => s.headOption.exists(_.isDigit))
        .getOrElse(fail("could not parse orca version from sbt output"))
      resolved = Published(repoRoot, version)

  override def munitFixtures: Seq[munit.AnyFixture[?]] = Seq(publishedRepo)

  test(
    "scala-cli runs a minimal script that links against the published library"
  ):
    val scriptDir = os.temp.dir()
    val script = scriptDir / "hello.sc"
    val version = publishedRepo().version
    os.write(
      script,
      s"""//> using scala 3.3.6
         |//> using repository ivy2Local
         |//> using dep org.virtuslab::orca:$version
         |//> using jvm 21
         |
         |import orca.{*, given}
         |
         |flow(args = OrcaArgs("smoke test")):
         |  println(s"userPrompt=$$userPrompt")
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

  /** Add new flow scripts here so they're picked up by the compile-check loop.
    * Each entry is a path under `plans/` (the seed scripts copy these `.sc`
    * files into the user's project at create-test-project time).
    */
  private val flowScripts: Seq[String] = Seq(
    "implement.sc",
    "implement-interactive.sc",
    "issue-pr-bugfix.sc",
    "epic.sc"
  )

  /** Each flow script is a real-world consumer of the library — when a
    * public-API rename or signature change ships, these scripts are the first
    * thing that breaks for users. Compile-checking them here closes the gap
    * between sbt's internal compile (which doesn't see `plans/`) and what a
    * fresh user actually runs.
    */
  for relPath <- flowScripts do
    test(s"plans/$relPath compiles via scala-cli"):
      val repoRoot = publishedRepo().repoRoot
      val scriptPath = repoRoot / "plans" / relPath
      val result = os
        .proc("scala-cli", "compile", scriptPath.toString)
        .call(cwd = repoRoot, check = false, mergeErrIntoOut = true)
      assertEquals(
        result.exitCode,
        0,
        s"scala-cli compile failed for plans/$relPath:\n${result.out.text()}"
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
