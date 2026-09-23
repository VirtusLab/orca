package orca.shell.create

import orca.shell.OrcaBuild
import orca.testkit.TempDirs

/** Compile-checks [[FlowAuthoring.skeletonFlow]]'s generated content via a real
  * `scala-cli` — the same kind of gap
  * `orca.shell.flows.BuiltInFlowsCompileTest` closes for the built-in flows:
  * sbt's own compile never sees generated shell-authored text, so a Scala 3
  * syntax regression (e.g. a comment-only `flow(...):` body) would otherwise
  * only surface once a user actually picked Create+hand.
  *
  * Needs this build in the local Ivy cache, so run it in one sbt session with
  * the publish: `ORCA_INTEGRATION=1 sbt publishLocal "shell/testOnly
  * *FlowAuthoringSmokeTest"`.
  */
class FlowAuthoringSmokeTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests() else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    10.minutes

  test("FlowAuthoring.skeletonFlow compiles via scala-cli"):
    val dir = TempDirs.dir()
    val script = dir / "skeleton.sc"
    os.write(script, FlowAuthoring.skeletonFlow(OrcaBuild.current))
    val result = os
      .proc("scala-cli", "compile", script.toString)
      .call(cwd = dir, check = false, mergeErrIntoOut = true)
    assertEquals(
      result.exitCode,
      0,
      s"scala-cli compile failed for the generated skeleton:\n${result.out.text()}"
    )
