package orca.shell.sessions

import java.time.Duration
import scala.jdk.OptionConverters.*

/** An attempt's [[AttemptStatus]] as the shell sees it now: an attempt still
  * [[AttemptStatus.Running]] whose process is gone is `Crashed` (ADR 0021 §8).
  */
private[shell] enum ObservedStatus:
  case Running, Succeeded, Failed, Crashed

private[shell] object ObservedStatus:

  /** `processAlive` answers whether the process that ran `attempt` still runs —
    * [[processAlive]] in production.
    */
  def of(
      attempt: AttemptRecord,
      processAlive: AttemptRecord => Boolean
  ): ObservedStatus =
    attempt.status match
      case AttemptStatus.Running =>
        if processAlive(attempt) then Running else Crashed
      case AttemptStatus.Succeeded => Succeeded
      case AttemptStatus.Failed    => Failed

  /** Whether `attempt.pid` names a live process that started no later than
    * `startedAt` (which the attempt takes inside that process) — a later start
    * means the pid was reused. The slack absorbs wall-clock steps, which shift
    * the start instants the OS reports; a crashed attempt's pid being reused
    * within it is negligible. An unknown start instant counts as alive.
    */
  def processAlive(attempt: AttemptRecord): Boolean =
    ProcessHandle
      .of(attempt.pid)
      .toScala
      .filter(_.isAlive)
      .exists: handle =>
        handle
          .info()
          .startInstant()
          .toScala
          .forall(!_.isAfter(attempt.startedAt.plus(ProcessStartSlack)))

  private val ProcessStartSlack = Duration.ofMinutes(1)
