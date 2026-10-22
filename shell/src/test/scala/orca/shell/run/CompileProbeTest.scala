package orca.shell.run

import orca.progress.FlowSource
import orca.shell.OrcaBuild
import orca.testkit.TempDirs

/** Runs [[FlowLauncher.compileArgv]] through a real `scala-cli`, so a flag
  * `scala-cli compile` rejects fails here instead of turning every flow failure
  * into a version-pin fallback offer. Pins a published release, so no
  * `publishLocal` is needed. Gated on `ORCA_INTEGRATION` (network + a real
  * scala-cli on PATH); skipped otherwise.
  */
class CompileProbeTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    5.minutes

  test("compileArgv compiles a valid flow"):
    val dir = TempDirs.dir()
    val script = dir / "flow.sc"
    os.write(script, "println(\"ok\")\n")
    val flow = LaunchedFlow(script, FlowSource.Catalog("flow.sc"))
    val argv =
      FlowLauncher.compileArgv(flow, OrcaBuild.Release("0.2.1"), dir)
    val result =
      os.proc(argv).call(cwd = dir, check = false, mergeErrIntoOut = true)
    assertEquals(result.exitCode, 0, result.out.text())
