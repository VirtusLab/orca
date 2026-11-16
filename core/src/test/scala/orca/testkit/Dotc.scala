package orca.testkit

import java.nio.file.Files

import scala.collection.mutable.ListBuffer

import dotty.tools.dotc.Main
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interfaces.Diagnostic as IDiagnostic
import dotty.tools.dotc.reporting.{Diagnostic, Reporter}

/** Compiles a fixture source with the real `dotc` against the calling module's
  * Test classpath, for tests that pin what does and doesn't compile.
  *
  * munit's `compileErrors` runs only the typer, so it misses capture checking,
  * separation checking and safe mode, which run after it.
  */
object Dotc:

  private class CollectingReporter extends Reporter:
    val diagnostics: ListBuffer[Diagnostic] = ListBuffer.empty
    def doReport(dia: Diagnostic)(using Context): Unit =
      val _ = diagnostics += dia

  /** sbt 2 starts forked tests through its own worker, so `java.class.path`
    * holds only the worker's jars; the build passes the real classpath as
    * `orca.test.classpath`.
    */
  private def classpath: String =
    sys.props.getOrElse(
      "orca.test.classpath",
      System.getProperty("java.class.path")
    )

  /** The error messages of compiling `source` as one file. */
  def errorsOf(source: String): List[String] =
    val srcDir = Files.createTempDirectory("dotc-src")
    val outDir = Files.createTempDirectory("dotc-out")
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

  private def deleteRecursively(dir: java.nio.file.Path): Unit =
    if Files.exists(dir) then
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete)
