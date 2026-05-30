package orca.runner

import java.io.PrintStream

/** The one-line-of-identity startup banner: ASCII-art wordmark, the running
  * version, and where this run's trace log lives. Printed once to the console
  * at flow start. Pure ASCII so it survives a non-UTF-8 console.
  */
private[orca] object OrcaBanner:

  private val Art: Seq[String] = Seq(
    "  ___                ",
    " / _ \\ _ __ ___ __ _ ",
    "| | | | '__/ __/ _` |",
    "| |_| | | | (_| (_| |",
    " \\___/|_|  \\___\\__,_|"
  )

  /** orca's version from the jar manifest (`Implementation-Version`, written by
    * sbt's `packageBin`). `"dev"` when running from class directories (a local
    * `sbt run` or the test suite), where there's no jar manifest.
    */
  def version: String =
    Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")

  /** Print the banner + a `version, logs: <path>` line to `out`. */
  def print(out: PrintStream, logFile: os.Path): Unit =
    Art.foreach(out.println)
    out.println(s"Orca $version, logs: $logFile")
    out.println()
