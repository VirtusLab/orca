package orca.shell.run

import orca.testkit.TempDirs

/** Pins the scala-cli behaviour [[FlowLauncher]]'s forced-version mechanism
  * depends on (ADR 0021 §2): a CLI `--dep` for a module REPLACES a `//> using
  * dep` pin of the same module rather than merging into highest-wins
  * resolution. That's what lets the shell force its own orca version onto a
  * flow that pins a different one. The behaviour is scala-cli-internal and
  * undocumented beyond the general "command line beats directives" rule, so
  * this canary catches a scala-cli upgrade that changes it. Gated on
  * `ORCA_INTEGRATION` (network + a real scala-cli on PATH); skipped otherwise.
  */
class DepOverrideCanaryTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    5.minutes

  private val pinnedVersion = "0.9.1"

  /** Writes a script pinning `com.lihaoyi::os-lib:0.9.1`, runs it via
    * `scala-cli run --command` with an overriding `--dep`, and returns the
    * classpath line. Per the research
    * (docs/research/shell/02-in-process-execution.md §S2), `--command` prints
    * one argument per line with `-D` lines before `-cp`, so the classpath is
    * the line immediately after the literal `-cp`.
    */
  private def classpathAfterOverride(overrideVersion: String): String =
    val scriptDir = TempDirs.dir()
    val script = scriptDir / "canary.sc"
    os.write(
      script,
      s"""//> using dep "com.lihaoyi::os-lib:$pinnedVersion"
         |println(os.pwd)
         |""".stripMargin
    )
    val result = os
      .proc(
        "scala-cli",
        "run",
        "--command",
        script.toString,
        "--dep",
        s"com.lihaoyi::os-lib:$overrideVersion"
      )
      .call(cwd = scriptDir, check = false, mergeErrIntoOut = true)
    val lines = result.out.lines().toIndexedSeq
    val cpIndex = lines.indexOf("-cp")
    assert(
      cpIndex >= 0 && cpIndex + 1 < lines.length,
      s"expected a -cp line followed by the classpath, got:\n${lines.mkString("\n")}"
    )
    lines(cpIndex + 1)

  test(
    "--dep overriding upward (0.9.1 -> 0.11.3) replaces the directive's pin on the classpath"
  ):
    val classpath = classpathAfterOverride("0.11.3")
    assert(
      classpath.contains("os-lib_3-0.11.3"),
      s"expected 0.11.3 on the classpath, got: $classpath"
    )
    assert(
      !classpath.contains("os-lib_3-0.9.1"),
      s"expected no 0.9.1 on the classpath, got: $classpath"
    )

  test(
    "--dep overriding downward (0.9.1 -> 0.8.1) replaces the directive's pin on the classpath"
  ):
    val classpath = classpathAfterOverride("0.8.1")
    assert(
      classpath.contains("os-lib_3-0.8.1"),
      s"expected 0.8.1 on the classpath, got: $classpath"
    )
    assert(
      !classpath.contains("os-lib_3-0.9.1"),
      s"expected no 0.9.1 on the classpath, got: $classpath"
    )
