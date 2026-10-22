package orca.backend

import orca.events.{OrcaEvent, OrcaListener}
import orca.safe.Logger
import orca.concurrency.sleep
import orca.safe.prelude.*
import orca.subprocess.PipedCliProcess

import scala.concurrent.duration.FiniteDuration
import scala.util.Properties.envOrNone

/** Finds processes still carrying an agent process's
  * [[orca.subprocess.EnvCookie]] once the scope that owned the process has torn
  * it down — the backstop for work an agent detached from orca's process tree
  * (`nohup … &`, `setsid`, a double fork), which parent-link teardown cannot
  * reach at all.
  *
  * '''Report-only by default.''' A survivor is announced (an `OrcaEvent.Step`
  * plus a WARN log) and left running; `ORCA_SWEEP_KILL=1` makes the same sweep
  * kill it.
  *
  * '''Linux only''' ([[PipedCliProcess.survivors]]): elsewhere the sweep finds
  * nothing and the whole feature is silently inert — deliberately, since a user
  * on a platform it cannot support has nothing to act on.
  */
private[backend] object EnvCookieSweep:

  private val log = Logger("orca.backend")

  /** `ORCA_SWEEP_KILL=1` — kill survivors instead of only naming them. */
  private val killsSurvivors: Boolean =
    envOrNone("ORCA_SWEEP_KILL").contains("1")

  /** Work the agent was already shutting down — an MCP server noticing stdin
    * EOF, say — can still be in the process table when teardown returns, and
    * reporting it every turn would bury the leaks worth seeing. So a first hit
    * is re-checked after this delay and only the second scan is announced. A
    * turn that leaked nothing never waits: its first scan comes back empty.
    */
  private val SettleDelay: FiniteDuration = 250.millis

  /** Announce what still carries `process`'s cookie. Runs as the owning scope's
    * finalizer, so it is total: a failure is logged and swallowed rather than
    * left to mask the scope's own outcome.
    */
  def afterScope(process: PipedCliProcess, events: OrcaListener): Unit =
    try
      if process.survivors().nonEmpty then
        sleep(SettleDelay)
        val settled = process.survivors()
        if settled.nonEmpty then announce(settled, events)
    catch case NonFatal(e) => log.debug("environment-cookie sweep failed", e)

  private def announce(
      survivors: List[ProcessHandle],
      events: OrcaListener
  ): Unit =
    if killsSurvivors then survivors.foreach(_.destroyForcibly().discard)
    val listed = survivors.map(describe).mkString(", ")
    val message =
      if killsSurvivors then
        s"Agent work outlived its process, killing: $listed"
      else
        s"Agent work outlived its process: $listed — not killed; set " +
          "ORCA_SWEEP_KILL=1 to have orca reap it"
    log.warn(message)
    events.onEvent(OrcaEvent.Step(message))

  /** The pid is what the reader acts on; the command is only there to recognise
    * it, so the executable's basename says as much as its install path (a JDK
    * path is half a terminal row on its own). Unreadable for a process that
    * exited between the scan and this call.
    */
  private def describe(handle: ProcessHandle): String =
    val command = handle.info.command.orElse("?")
    s"${handle.pid} (${command.drop(command.lastIndexOf('/') + 1)})"
