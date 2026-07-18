package orca.runner

import java.io.PrintStream

/** The one-line-of-identity startup banner: the running version and where this
  * run's trace log lives. Printed once to the console at flow start. Pure ASCII
  * so it survives a non-UTF-8 console.
  */
private[orca] object OrcaBanner:

  /** orca's version from the jar manifest (`Implementation-Version`). `"dev"`
    * when running from class directories (local `sbt run` or the test suite),
    * where there's no jar manifest.
    */
  def version: String =
    Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")

  /** Print the `Orca <version>, logs: <path>` line to `out`. `None` when the
    * trace file couldn't be created (best-effort logging).
    */
  def print(out: PrintStream, logFile: Option[os.Path]): Unit =
    val where = logFile.map(_.toString).getOrElse("(trace file unavailable)")
    out.println(s"Orca $version, logs: $where")
    out.println()
