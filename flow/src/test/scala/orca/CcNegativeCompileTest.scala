package orca

import java.nio.file.Files

import scala.collection.mutable.ListBuffer

import dotty.tools.dotc.Main
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interfaces.Diagnostic as IDiagnostic
import dotty.tools.dotc.reporting.{Diagnostic, Reporter}

/** Capture-checking enforcement, pinned as automated negative-compile tests. A
  * manual spike first proved that the checked fan-out shape — an impure-typed
  * thunk list (`Seq[() => Int]`) routed through [[orca.CheckedPar]] — rejects
  * an exclusive-capability capture; this suite pins that proof so a compiler
  * upgrade or a wrapper edit can't silently drop the enforcement. Note the
  * rejection fires at the fixture's impure `Seq[() => Int]` ascription (the
  * widening that "hides" the exclusive token), not at the wrapper boundary
  * itself — see [[orca.CheckedPar]]'s object doc.
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

  /** Fixture implementing [[orca.review.ReviewerSelector]] (ADR 0011's
    * 2026-07-08 amendment: `prepare` returns a pure `->` arrow that may only
    * narrow over `history`). `body` is that returned lambda; the surrounding
    * shape — imports, `prepare`'s signature, the `using ctx: FlowContext, ev:
    * InStage` capabilities in scope — is identical for the (d)/(e) pair below,
    * so purity of the returned lambda is the only axis under test.
    */
  private def selectorFixture(body: String): String =
    s"""package orca.review
       |import language.experimental.captureChecking
       |import language.experimental.separationChecking
       |import orca.{FlowContext, InStage}
       |import orca.plan.Title
       |object SelectorFixture:
       |  val selector: ReviewerSelector = new ReviewerSelector:
       |    def prepare(
       |        all: List[RosterEntry[?]],
       |        taskTitle: Title,
       |        changedFiles: List[String]
       |    )(using ctx: FlowContext, ev: InStage): List[ReviewBatch] -> List[RosterEntry[?]] =
       |      $body
       |""".stripMargin

  test(
    "(d) NEGATIVE: a ReviewerSelector whose returned arrow captures `ev` (InStage) fails to compile"
  ):
    val errors =
      compileErrorsOf(selectorFixture("history => { val _ = ev; all }"))
    assertCaptureEscape(errors)

  test("(e) POSITIVE: a pure history-narrowing ReviewerSelector compiles"):
    val errors = compileErrorsOf(
      selectorFixture(
        "history => history.headOption match { case None => all; " +
          "case Some(batch) => batch.reviewersWithIssues }"
      )
    )
    assert(
      errors.isEmpty,
      s"a pure history-narrowing selector (no `using` capture) must compile, got: $errors"
    )

  /** Assert `prepare`'s returned arrow was rejected for capturing a `using`
    * capability into a pure `->` type — the compile-time form of the
    * ReviewerSelector contract (ADR 0011, 2026-07-08 amendment; see also
    * [[orca.review.ReviewerSelector]]'s trait doc): the returned narrowing may
    * only close over `history`, not the loop's capabilities.
    *
    * This is a DIFFERENT checker pass than [[assertSeparationFailure]]: that
    * one is separation checking rejecting an EXCLUSIVE capability at a fork
    * boundary ("Separation failure"); this is plain capture checking rejecting
    * ANY capability (`ev: InStage` is normally freely shareable — see test (a))
    * from flowing into a capture-set-`{}` pure arrow. Manually compiling the
    * capturing fixture surfaces: "Note that capability `ev` cannot flow into
    * capture set {}." — that phrase, not "Separation failure", is the marker
    * asserted below.
    */
  private def assertCaptureEscape(errors: List[String]): Unit =
    assert(
      errors.nonEmpty,
      "expected a compile error for a ReviewerSelector arrow capturing `ev`"
    )
    assert(
      errors.exists(_.contains("cannot flow into capture set")),
      s"expected a capture-checking error rejecting the `ev` capture, got: $errors"
    )

end CcNegativeCompileTest
