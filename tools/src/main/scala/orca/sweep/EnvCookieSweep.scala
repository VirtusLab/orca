package orca.sweep

import orca.events.{OrcaEvent, OrcaListener}

import org.slf4j.LoggerFactory
import ox.{discard, sleep}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.StreamConverters.*
import scala.util.control.NonFatal

/** Finds processes still carrying a turn's [[EnvCookie]] once that turn has
  * been torn down — the backstop for work an agent detached from orca's process
  * tree (`nohup … &`, `setsid`, a double fork), which parent-link teardown
  * cannot reach at all.
  *
  * '''Report-only by default.''' A survivor is announced (an `OrcaEvent.Step`
  * plus a WARN log) and left running; `ORCA_SWEEP_KILL=1` makes the same sweep
  * kill it.
  *
  * '''Linux only.''' The scan reads `/proc/<pid>/environ`. No equivalent has
  * been verified on macOS, so [[sweep]] there finds nothing and the whole
  * feature is silently inert — deliberately, since a user on a platform it
  * cannot support has nothing to act on. A sweep that appears to do nothing on
  * a Mac is working as intended, not broken.
  */
private[orca] object EnvCookieSweep:

  private val log = LoggerFactory.getLogger("orca.sweep")

  /** `ORCA_SWEEP_KILL=1` — kill survivors instead of only naming them. */
  private val killsSurvivors: Boolean =
    sys.env.get("ORCA_SWEEP_KILL").contains("1")

  private val ProcFs: Path = Path.of("/proc")

  /** `environ`'s entry separator. */
  private val Nul: Char = 0

  /** Whether this machine exposes readable per-process environments. */
  val supported: Boolean = Files.isDirectory(ProcFs)

  /** Work the agent was already shutting down — an MCP server noticing stdin
    * EOF, say — can still be in the process table when teardown returns, and
    * reporting it every turn would bury the leaks worth seeing. So a first hit
    * is re-checked after this delay and only the second scan is announced. A
    * turn that leaked nothing never waits: its first scan comes back empty.
    */
  private val SettleDelay: FiniteDuration = 250.millis

  /** Sweep for `cookie` and announce what still carries it. Runs in the
    * teardown `finally` of every turn, so it is total: a failure is logged and
    * swallowed rather than left to mask the turn's own outcome. `None` — a
    * conversation with no process of its own — is a no-op, as is a platform
    * [[sweep]] cannot scan.
    */
  def afterTurn(cookie: Option[EnvCookie], events: OrcaListener): Unit =
    cookie.foreach: c =>
      try
        if sweep(c).nonEmpty then
          sleep(SettleDelay)
          val settled = sweep(c)
          if settled.nonEmpty then announce(settled, events)
      catch
        // Teardown can be interrupted mid-settle; hand the interrupt back
        // rather than swallowing it along with the diagnostic.
        case _: InterruptedException => Thread.currentThread().interrupt()
        case NonFatal(e) => log.debug("environment-cookie sweep failed", e)

  /** Live processes whose environment carries `cookie`; empty where the scan is
    * unsupported. Orca's own JVM is skipped, so a sweep can never name — or,
    * with the kill switch on, kill — the process running it.
    */
  def sweep(cookie: EnvCookie): List[ProcessHandle] =
    if !supported then Nil
    else
      val entry = cookie.environEntry
      val self = ProcessHandle.current().pid
      ProcessHandle
        .allProcesses()
        .toScala(List)
        .filter(h => h.pid != self && carriesEntry(h.pid, entry))

  /** Compares whole NUL-delimited entries, so a variable whose name merely ends
    * with ours cannot match. A process that exited mid-scan and one owned by
    * another user both read as `false` — the file is gone or unreadable, which
    * is all this needs to decide.
    */
  private def carriesEntry(pid: Long, entry: String): Boolean =
    try
      new String(
        Files.readAllBytes(ProcFs.resolve(pid.toString).resolve("environ")),
        UTF_8
      ).split(Nul).contains(entry)
    catch case NonFatal(_) => false

  private def announce(
      survivors: List[ProcessHandle],
      events: OrcaListener
  ): Unit =
    if killsSurvivors then survivors.foreach(_.destroyForcibly().discard)
    val listed = survivors.map(describe).mkString(", ")
    val message =
      if killsSurvivors then s"Agent work outlived this turn, killing: $listed"
      else
        s"Agent work outlived this turn: $listed — not killed; set " +
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
