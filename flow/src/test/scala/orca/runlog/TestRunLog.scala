package orca.runlog

import orca.{AttemptId, OrcaDir, RunKey}
import orca.sessions.SessionRecord
import ox.{Ox, supervised}

import java.time.Instant

/** A real [[RunEventLog]], for tests that model attempts as separate processes
  * over one work dir.
  */
object TestRunLog:

  /** Runs `f` as one attempt against the event log of the run keyed `key` in
    * `dir`. Every event is in the file once this returns, even when `f` throws.
    * Writes no `RunSucceeded`, so the next attempt sees this one's sessions.
    */
  def attempt[T](dir: os.Path, key: RunKey)(f: RunEventLog => T): T =
    supervised:
      val log = start(dir, key)
      try f(log)
      finally log.finish(AttemptOutcome.Failed)

  /** Starts a log in the enclosing scope; the caller calls `finish`. */
  def start(dir: os.Path, key: RunKey)(using Ox): RunEventLog =
    val clock = () => Instant.now()
    RunEventLog.start(
      dir,
      key,
      AttemptId(clock(), pid = ProcessHandle.current().pid()),
      orcaVersion = "test",
      flowName = None,
      tracePath = None,
      clock = clock
    )

  /** The session records a next attempt of the run keyed `key` in `dir` would
    * load.
    */
  def records(dir: os.Path, key: RunKey): List[SessionRecord] =
    SessionProjection.records(
      RunEventReader.read(OrcaDir.eventLogPath(dir, key))
    )
