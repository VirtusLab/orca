package orca.runner.terminal

/** Publishes the library to the local Ivy cache and runs a minimal `.sc` script
  * with `scala-cli` to prove the library is consumable end-to-end. Gated on
  * ORCA_INTEGRATION because it runs real sbt + scala-cli.
  */
class ScalaCliSmokeTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    10.minutes

  test(
    "scala-cli runs a minimal script that links against the published library"
  ):
    val repoRoot = findRepoRoot()

    // publishLocal everything
    val publishResult = os
      .proc("sbt", "publishLocal")
      .call(cwd = repoRoot, check = false)
    assertEquals(
      publishResult.exitCode,
      0,
      s"publishLocal failed: ${publishResult.err.text()}"
    )

    val scriptDir = os.temp.dir()
    val script = scriptDir / "hello.sc"
    os.write(
      script,
      """//> using scala 3.3.6
        |//> using repository ivy2Local
        |//> using dep com.virtuslab::orca-cli:0.1.0-SNAPSHOT
        |//> using dep com.virtuslab::orca-core:0.1.0-SNAPSHOT
        |//> using dep com.virtuslab::orca-claude:0.1.0-SNAPSHOT
        |//> using dep com.virtuslab::orca-codex:0.1.0-SNAPSHOT
        |//> using jvm 21
        |
        |import orca.*
        |import orca.cli.OrcaArgs
        |
        |orca(args = OrcaArgs("smoke test")):
        |  println(s"userPrompt=$userPrompt")
        |""".stripMargin
    )

    val runResult = os
      .proc("scala-cli", "run", "--ttl", "0", script.toString)
      .call(cwd = scriptDir, check = false, mergeErrIntoOut = true)
    val runOutput = runResult.out.text()
    assertEquals(runResult.exitCode, 0, s"scala-cli run failed: $runOutput")
    assert(
      runOutput.contains("userPrompt=smoke test"),
      s"expected stdout to contain 'userPrompt=smoke test', got: $runOutput"
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
