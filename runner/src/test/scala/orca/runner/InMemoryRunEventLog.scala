package orca.runner

import orca.events.OrcaEvent
import orca.gitref.BranchName
import orca.runlog.{AttemptOutcome, RunEventLog}
import orca.sessions.TestSessionStore

/** A [[RunEventLog]] that keeps session records in memory and records nothing
  * else, for tests that call `FlowLifecycle` directly.
  */
class InMemoryRunEventLog extends TestSessionStore, RunEventLog:
  def onEvent(event: OrcaEvent): Unit = ()
  def runSucceeded(branch: BranchName, published: Option[String]): Unit = ()
  def finish(outcome: AttemptOutcome): Unit = ()
