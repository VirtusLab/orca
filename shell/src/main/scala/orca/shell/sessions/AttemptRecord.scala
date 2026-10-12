package orca.shell.sessions

import orca.gitref.BranchName

import java.time.Instant

/** Where an attempt stands, as [[AttemptRecord.status]] records it: `Running`
  * until the attempt records how it ended.
  */
private[shell] enum AttemptStatus:
  case Running, Succeeded, Failed

/** One attempt as the shell's "continue a session" listing sees it, projected
  * from the attempt's events in its run's event log (ADR 0025). A stale
  * [[AttemptStatus.Running]] with a dead `pid` means the attempt crashed, and
  * the shell still offers its recorded sessions.
  *
  * `branch` is the branch the attempt bound to; `None` until `BranchBound`
  * fires, so an attempt that failed before binding has none.
  *
  * `sessions` is empty until the first `SessionCommitted` and only grows: a
  * session keeps its position once recorded, which the shell's `orca continue
  * <id>` selector relies on.
  */
private[shell] case class AttemptRecord(
    orcaVersion: String,
    flow: Option[String],
    workDir: String,
    branch: Option[BranchName],
    pid: Long,
    startedAt: Instant,
    status: AttemptStatus,
    sessions: List[RecordedSession]
)
