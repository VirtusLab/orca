package orca.runner

import java.io.PrintStream

/** The startup banner: the running version and where this run's progress log,
  * event log and this attempt's trace log live. Printed once to the console at
  * flow start. Pure ASCII so it survives a non-UTF-8 console.
  */
private[orca] object OrcaBanner:

  /** orca's version from the jar manifest (`Implementation-Version`). `"dev"`
    * when running from class directories (local `sbt run` or the test suite),
    * where there's no jar manifest.
    */
  def version: String =
    Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")

  /** Print the version line followed by the `progress:`, `events:` and `trace:`
    * path lines to `out`. `trace` is `None` when the trace file couldn't be
    * created (best-effort logging).
    */
  def print(
      out: PrintStream,
      progress: os.Path,
      events: os.Path,
      trace: Option[os.Path]
  ): Unit =
    val traceWhere = trace.map(_.toString).getOrElse("(trace file unavailable)")
    out.println(s"Orca $version")
    out.println(s"  progress: $progress")
    out.println(s"  events:   $events")
    out.println(s"  trace:    $traceWhere")
    out.println()
