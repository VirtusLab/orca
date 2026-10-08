package orca.shell.sessions

import orca.AttemptId
import orca.agents.SessionKey
import orca.runlog.{AttemptOutcome, RunEvent}

/** One attempt as the shell lists it, projected from that attempt's events in
  * its run's event log.
  */
private[sessions] object AttemptProjection:

  /** `events` are attempt `id`'s, in file order. `None` when they hold no
    * `AttemptStarted`: without it the attempt has no work dir to resume in.
    *
    * Sessions come from `SessionCommitted`, one per `(backend,
    * conversationKey)`, in first-seen order ([[SessionRef]]'s position); each
    * takes its stage and last-active time from its latest commit, and keeps a
    * minted key once seen, since a chat turn continuing a durable session
    * carries none.
    */
  def of(id: AttemptId, events: List[RunEvent]): Option[AttemptRecord] =
    events
      .collectFirst { case s: RunEvent.AttemptStarted => s }
      .map: started =>
        val finished = events.collectFirst:
          case f: RunEvent.AttemptFinished => f
        AttemptRecord(
          orcaVersion = started.orcaVersion,
          flow = started.flow,
          workDir = started.workDir,
          branch = events.collect { case b: RunEvent.BranchBound =>
            b.branch
          }.lastOption,
          pid = started.pid,
          startedAt = id.startedAt,
          status =
            finished.fold(AttemptStatus.Running)(f => statusOf(f.outcome)),
          sessions = sessionsOf(events)
        )

  private def statusOf(outcome: AttemptOutcome): AttemptStatus =
    outcome match
      case AttemptOutcome.Succeeded => AttemptStatus.Succeeded
      case AttemptOutcome.Failed    => AttemptStatus.Failed

  private def sessionsOf(events: List[RunEvent]): List[RecordedSession] =
    val commits = events.collect { case c: RunEvent.SessionCommitted => c }
    val byKey = commits.groupBy(c => (c.backend, c.conversationKey))
    commits
      .map(c => (c.backend, c.conversationKey))
      .distinct
      .map: key =>
        val ofKey = byKey(key)
        sessionOf(ofKey.last, ofKey.flatMap(_.minted).lastOption)

  private def sessionOf(
      commit: RunEvent.SessionCommitted,
      minted: Option[SessionKey]
  ): RecordedSession =
    RecordedSession(
      backend = commit.backend,
      wireId = commit.wireId,
      agent = commit.agent,
      role = commit.role,
      stage = commit.stage.map(_.name),
      minted = minted,
      lastActiveAt = commit.at
    )
