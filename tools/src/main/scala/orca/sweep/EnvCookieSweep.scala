package orca.sweep

import orca.events.{OrcaEvent, OrcaListener}

import org.slf4j.LoggerFactory
import ox.discard

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.StreamConverters.*
import scala.util.control.NonFatal

/** Result of one scan of the machine's process table. */
private[orca] enum SweepOutcome:
  /** No readable per-process environment on this platform, so nothing can be
    * found. Distinguished from an empty scan so "we cannot look" is never
    * reported as "nothing leaked".
    */
  case Unsupported

  /** The scan ran; `survivors` are the live processes carrying the cookie,
    * normally empty.
    */
  case Scanned(survivors: List[ProcessHandle])

/** Finds processes still carrying a turn's [[EnvCookie]] after that turn has
  * been torn down — the backstop for work an agent detached from orca's process
  * tree (`nohup … &`, `setsid`, a double fork), which parent-link teardown
  * cannot reach at all.
  *
  * '''Report-only by default.''' A survivor is announced (an `OrcaEvent.Step`
  * plus a WARN log) and left running. `ORCA_SWEEP_KILL=1` turns the same
  * announcement into a kill; nothing else enables it.
  *
  * '''Linux only.''' The scan reads `/proc/<pid>/environ`. No equivalent has
  * been verified on macOS, so the sweep says [[SweepOutcome.Unsupported]] there
  * rather than guessing and silently finding nothing.
  */
private[orca] object EnvCookieSweep:

  private val log = LoggerFactory.getLogger("orca.sweep")

  /** `ORCA_SWEEP_KILL=1` — kill survivors instead of only naming them. Off by
    * default: this ships as a diagnostic, so a turn's leftovers are reported
    * and left alone unless the variable is set.
    */
  private val killsSurvivors: Boolean =
    sys.env.get("ORCA_SWEEP_KILL").contains("1")

  private val ProcFs: Path = Path.of("/proc")

  /** Whether this machine exposes readable per-process environments. */
  val supported: Boolean = Files.isDirectory(ProcFs)

  /** Latches the one-time "cannot look here" notice: the sweep runs at every
    * turn boundary, and a static platform fact repeated per turn is noise.
    */
  private val unsupportedAnnounced = new AtomicBoolean(false)

  /** Sweep for `cookie` and announce what still carries it. Runs in the
    * teardown `finally` of every turn, so it is total: a failure is logged and
    * swallowed rather than left to mask the turn's own outcome. `None` — a
    * conversation with no process of its own — is a no-op.
    */
  def afterTurn(cookie: Option[EnvCookie], events: OrcaListener): Unit =
    cookie.foreach: c =>
      try
        sweep(c) match
          case SweepOutcome.Unsupported        => announceUnsupported(events)
          case SweepOutcome.Scanned(Nil)       => ()
          case SweepOutcome.Scanned(survivors) => announce(survivors, events)
      catch case NonFatal(e) => log.debug("environment-cookie sweep failed", e)

  /** Live processes whose environment carries `cookie`. Orca's own JVM is
    * skipped, so a sweep can never name — or, with the kill switch on, kill —
    * the process running it.
    */
  def sweep(cookie: EnvCookie): SweepOutcome =
    if !supported then SweepOutcome.Unsupported
    else
      val entry = cookie.environEntry.getBytes(UTF_8)
      val self = ProcessHandle.current().pid
      SweepOutcome.Scanned(
        ProcessHandle
          .allProcesses()
          .toScala(List)
          .filter(h => h.pid != self && carriesEntry(h.pid, entry))
      )

  /** A process that exited mid-scan and one owned by another user both read as
    * `false` — the `environ` file is gone or unreadable, which is all this
    * needs to decide.
    */
  private def carriesEntry(pid: Long, entry: Array[Byte]): Boolean =
    try
      containsEntry(
        Files.readAllBytes(ProcFs.resolve(pid.toString).resolve("environ")),
        entry
      )
    catch case NonFatal(_) => false

  /** Walks the NUL-delimited `environ` blob entry by entry and compares whole
    * entries, so neither a variable whose name merely ends with ours nor one
    * whose value merely starts with the cookie can match. Byte-level and
    * allocation-free: this runs over every process on the machine at every turn
    * boundary.
    */
  private def containsEntry(
      environ: Array[Byte],
      entry: Array[Byte]
  ): Boolean =
    var start = 0
    var found = false
    while !found && start < environ.length do
      var end = start
      while end < environ.length && environ(end) != 0 do end += 1
      found = end - start == entry.length &&
        java.util.Arrays.equals(environ, start, end, entry, 0, entry.length)
      start = end + 1
    found

  private def announce(
      survivors: List[ProcessHandle],
      events: OrcaListener
  ): Unit =
    val listed = survivors.map(describe).mkString(", ")
    val message =
      if killsSurvivors then
        survivors.foreach(_.destroyForcibly().discard)
        s"Agent work outlived this turn and was killed: $listed"
      else
        s"Agent work outlived this turn: $listed — not killed; set " +
          "ORCA_SWEEP_KILL=1 to have orca reap it"
    log.warn(message)
    events.onEvent(OrcaEvent.Step(message))

  private def announceUnsupported(events: OrcaListener): Unit =
    if unsupportedAnnounced.compareAndSet(false, true) then
      val message =
        "Cannot tell whether agent work outlived a turn: this platform has no " +
          "/proc, and orca reads process environments nowhere else"
      log.warn(message)
      events.onEvent(OrcaEvent.Step(message))

  /** The command is unreadable for a process that exited between the scan and
    * this call.
    */
  private def describe(handle: ProcessHandle): String =
    s"${handle.pid} (${handle.info.command.orElse("?")})"
