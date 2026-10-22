package orca.runner.scalacli

import orca.testkit.GitRepo

/** Runs a minimal flow script against the locally published library — the
  * end-to-end counterpart to `orca.shell.flows.BuiltInFlowsCompileTest`: it
  * checks that a published artifact actually starts a flow, not just that it
  * type-checks.
  *
  * Needs the library in the local Ivy cache under `orca.build.version`, so run
  * it in one sbt session with the publish: `ORCA_INTEGRATION=1 sbt publishLocal
  * "runner/testOnly *ScalaCliSmokeTest"`. Stays out of CI even then: starting a
  * flow names the branch with the cheap model, so it needs an authenticated
  * `claude` and spends tokens.
  */
class ScalaCliSmokeTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    20.minutes

  private val scalaVersion: String = sys.props.getOrElse(
    "orca.build.scalaVersion",
    sys.error(
      "-Dorca.build.scalaVersion unset — see scalaVersionProperty in build.sbt"
    )
  )

  private val version: String = sys.props.getOrElse(
    "orca.build.version",
    sys.error(
      "-Dorca.build.version unset — see buildVersionProperty in build.sbt"
    )
  )

  test(
    "scala-cli runs a minimal script that links against the published library"
  ):
    // A flow refuses to run outside a git repo and needs a commit to diff
    // against — the starting point `create-test-project.sh` also seeds.
    val scriptDir = GitRepo.seeded()
    val script = scriptDir / "hello.sc"
    os.write(
      script,
      s"""//> using scala $scalaVersion
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
