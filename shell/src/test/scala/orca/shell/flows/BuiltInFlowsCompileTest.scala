package orca.shell.flows

import orca.shell.OrcaBuild
import orca.testkit.TempDirs

/** Compiles every built-in flow with `scala-cli`, staged by the production
  * snapshot path: [[BuiltInFlows.extracted]] repins each script's `//> using
  * dep` to the running version and inserts `//> using repository ivy2Local`.
  * That covers both halves of the promise in one check — the flows still
  * compile against the working tree's API, and the rewrite a snapshot applies
  * really does produce resolvable scripts. Compiling the scripts as committed
  * under `flows/` would instead resolve the last release from Maven Central,
  * and pass whatever the working tree does.
  *
  * Needs this build in the local Ivy cache, so run it in one sbt session with
  * the publish: `ORCA_INTEGRATION=1 sbt publishLocal "shell/testOnly
  * *BuiltInFlowsCompileTest"`. Gated on `ORCA_INTEGRATION` like the other
  * suites that shell out; CI runs it as its own job
  * (`.github/workflows/ci.yml`), being the one integration suite that needs no
  * credentials — only a `scala-cli` on PATH.
  */
class BuiltInFlowsCompileTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    20.minutes

  /** Extraction target is a throwaway cache home, so the developer's real
    * `~/.cache/orca` is untouched.
    */
  private lazy val extractedFlows: os.Path =
    BuiltInFlows.extracted(TempDirs.dir(), OrcaBuild.current)

  for name <- BuiltInFlows.names do
    test(s"built-in $name compiles via scala-cli"):
      // A release version takes the other `extracted` branch: pin untouched, no
      // ivy2Local, so this would compile against Maven Central — where a
      // mid-release build's artifact isn't published yet. The commit a tag
      // points at was already checked as a snapshot.
      val isSnapshot = OrcaBuild.current match
        case OrcaBuild.Snapshot(_) => true
        case OrcaBuild.Release(_)  => false
      assume(
        isSnapshot,
        s"${OrcaBuild.current.version} is a release; built-in flows resolve from Maven Central"
      )
      val script = extractedFlows / name
      val result = os
        .proc("scala-cli", "compile", script.toString)
        .call(cwd = extractedFlows, check = false, mergeErrIntoOut = true)
      assertEquals(
        result.exitCode,
        0,
        s"scala-cli compile failed for $name:\n${result.out.text()}"
      )
