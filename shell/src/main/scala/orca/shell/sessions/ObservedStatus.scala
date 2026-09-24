package orca.shell.sessions

import orca.runner.manifest.{AttemptManifest, AttemptStatus}

import java.time.Duration
import scala.jdk.OptionConverters.*

/** An attempt's [[AttemptStatus]] as the shell sees it now: a manifest still
  * [[AttemptStatus.Running]] whose process is gone is `Crashed` (ADR 0021 §8).
  */
private[shell] enum ObservedStatus:
  case Running, Succeeded, Failed, Crashed

private[shell] object ObservedStatus:

  /** `processAlive` answers whether the process that wrote `manifest` still
    * runs — [[processAlive]] in production.
    */
  def of(
      manifest: AttemptManifest,
      processAlive: AttemptManifest => Boolean
  ): ObservedStatus =
    manifest.status match
      case AttemptStatus.Running =>
        if processAlive(manifest) then Running else Crashed
      case AttemptStatus.Succeeded => Succeeded
      case AttemptStatus.Failed    => Failed

  /** Whether `manifest.pid` names a live process that started no later than
    * `startedAt` (which the attempt takes inside that process) — a later start
    * means the pid was reused. The slack absorbs wall-clock steps, which shift
    * the start instants the OS reports; a crashed attempt's pid being reused
    * within it is negligible. An unknown start instant counts as alive.
    */
  def processAlive(manifest: AttemptManifest): Boolean =
    ProcessHandle
      .of(manifest.pid)
      .toScala
      .filter(_.isAlive)
      .exists: handle =>
        handle
          .info()
          .startInstant()
          .toScala
          .forall(!_.isAfter(manifest.startedAt.plus(ProcessStartSlack)))

  private val ProcessStartSlack = Duration.ofMinutes(1)
