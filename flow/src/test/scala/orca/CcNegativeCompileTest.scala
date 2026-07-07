package orca

import java.nio.file.Files

import scala.collection.mutable.ListBuffer

import dotty.tools.dotc.Main
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interfaces.Diagnostic as IDiagnostic
import dotty.tools.dotc.reporting.{Diagnostic, Reporter}

/** Capture-checking enforcement, pinned as automated negative-compile tests
  * (Epic 0 task 0.6). Task 0.5 proved by hand that routing a fan-out through
  * [[orca.CheckedPar]] rejects an exclusive-capability capture; this suite pins
  * that proof so a compiler upgrade or a wrapper edit can't silently drop the
  * enforcement.
  *
  * Why an in-process compiler, not munit's `compileErrors`: that macro runs
  * only the typer, but capture/separation checking runs post-typer — so a
  * `compileErrors` version of the exclusive-capture cases would report ZERO
  * errors and pass vacuously. (`orcacaps.InStageNegativeTest` legitimately
  * stays on `compileErrors`: its negatives ARE typer errors.) Here we invoke
  * the real `dotc` ([[dotty.tools.dotc.Main]]) on fixture sources, against this
  * module's own Test classpath, so the full checked-compilation pipeline runs.
  *
  * Fixtures declare `package orca` because [[orca.CheckedPar]] is
  * `private[orca]` — an internal funnel, not meant on the `import orca.*` user
  * namespace. They still pass the capability token in as a method parameter
  * rather than minting it via `InStage.unsafe`: passing an exclusive parameter
  * into the parallel closures is exactly the smuggling separation checking must
  * reject, and it keeps each fixture to one token, one wrapper call, two
  * closures — no `.unsafe` door, no Ox scope.
  */
class CcNegativeCompileTest extends munit.FunSuite:

  /** Collects error/warning diagnostics from a `dotc` run instead of parsing
    * stdout — a custom [[Reporter]] is the driver API's supported inspection
    * point.
    */
  private class CollectingReporter extends Reporter:
    val diagnostics: ListBuffer[Diagnostic] = ListBuffer.empty
    def doReport(dia: Diagnostic)(using Context): Unit =
      val _ = diagnostics += dia

  /** The Test classpath, materialised by the build's `resourceGenerators` (flow
    * tests aren't forked, so `java.class.path` would only be sbt's launcher
    * classpath). Loaded once and reused across every fixture compile.
    */
  private lazy val classpath: String =
    val is = getClass.getResourceAsStream("/cc-test-classpath.txt")
    assert(is != null, "cc-test-classpath.txt resource missing — check build")
    try new String(is.readAllBytes(), "UTF-8").trim
    finally is.close()

  /** Compile one fixture source with `dotc` and return its error messages. Each
    * call spins a fresh reporter and output dir; the classpath (the expensive
    * part to assemble) is shared.
    */
  private def compileErrorsOf(source: String): List[String] =
    val srcDir = Files.createTempDirectory("cc-src")
    val outDir = Files.createTempDirectory("cc-out")
    try
      val srcFile = srcDir.resolve("Fixture.scala")
      Files.writeString(srcFile, source)
      val reporter = new CollectingReporter
      val args = Array(
        "-classpath",
        classpath,
        "-d",
        outDir.toString,
        srcFile.toString
      )
      val _ = Main.process(args, reporter)
      reporter.diagnostics.toList
        .filter(_.level >= IDiagnostic.ERROR)
        .map(_.message)
    finally
      deleteRecursively(srcDir)
      deleteRecursively(outDir)

  /** Recursively removes a directory tree created for one fixture compile. */
  private def deleteRecursively(dir: java.nio.file.Path): Unit =
    if Files.exists(dir) then
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete)

  /** Fan-out fixture whose closures capture a `tok` of the given token type,
    * routed through the real `orca.CheckedPar.mapParUnordered`. Identical shape
    * across cases — only the token type varies — so the positive and negative
    * verdicts differ solely by the token's capability kind (shared vs.
    * exclusive), which is precisely what's under test.
    */
  private def fixture(tokenType: String): String =
    s"""package orca
       |import language.experimental.captureChecking
       |import language.experimental.separationChecking
       |object Fixture:
       |  def go(tok: $tokenType): List[Int] =
       |    val tasks: Seq[() => Int] =
       |      Seq(() => { val _ = tok; 1 }, () => { val _ = tok; 2 })
       |    CheckedPar.mapParUnordered(tasks.size)(tasks)(r => println(r))
       |""".stripMargin

  // Meta-test (harness RED guard): proves the harness actually detects
  // compile errors at all — otherwise every negative case below would pass
  // vacuously. A plain type error must surface as a non-empty error list.
  test("harness detects a plain compile error"):
    val errors = compileErrorsOf(
      "object Broken:\n  val n: Int = \"not an int\"\n"
    )
    assert(
      errors.nonEmpty,
      "harness failed to surface a trivial type error — it cannot be trusted"
    )

  test("(a) POSITIVE: capturing InStage (shared) compiles"):
    val errors = compileErrorsOf(fixture("orca.InStage"))
    assert(
      errors.isEmpty,
      s"the load-bearing shared InStage fan-out capture must compile, got: $errors"
    )

  test("(b) NEGATIVE: capturing WorkspaceWrite (exclusive) fails to compile"):
    val errors = compileErrorsOf(fixture("orca.WorkspaceWrite"))
    assertSeparationFailure("WorkspaceWrite", errors)

  test("(c) NEGATIVE: capturing FlowControl (exclusive) fails to compile"):
    val errors = compileErrorsOf(fixture("orca.FlowControl"))
    assertSeparationFailure("FlowControl", errors)

  /** Assert the fan-out was rejected specifically by separation checking — not
    * by some unrelated error. Both phrasings the checker emits for an exclusive
    * fan-out capture ("...hides parameter tok. The parameter needs to be
    * annotated with consume..." for a parameter-bound token, "Illegal access to
    * {} which is hidden by the previous definition..." for a local one) start
    * with "Separation failure", so that exact marker is required. The message
    * does NOT reliably name the capability type, so matching on the token name
    * would open a vacuity loophole (e.g. a "not found: type WorkspaceWrite"
    * classpath error would sneak through).
    */
  private def assertSeparationFailure(
      token: String,
      errors: List[String]
  ): Unit =
    assert(
      errors.nonEmpty,
      s"expected a compile error capturing an exclusive $token into the fan-out"
    )
    assert(
      errors.exists(_.contains("Separation failure")),
      s"expected a separation-checking error rejecting the $token capture, got: $errors"
    )

end CcNegativeCompileTest
