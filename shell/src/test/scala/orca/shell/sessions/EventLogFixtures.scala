package orca.shell.sessions

import orca.{AttemptId, OrcaDir, RunKey, StagePath}
import orca.agents.{BackendTag, SessionKey}
import orca.testkit.branchName
import orca.runlog.{AttemptOutcome, RunEvent}

import java.time.Instant

/** Attempts and sessions as the shell's tests build them, written to disk as
  * the events [[EventLogReader]] projects them from, through the production
  * codec, so a fixture can never drift from the shape the reader accepts.
  */
private[shell] object EventLogFixtures:

  def attemptRecord(
      workDir: String = "/work",
      startedAt: String = "2026-07-18T10:00:00Z",
      pid: Long = 1,
      status: AttemptStatus = AttemptStatus.Succeeded,
      sessions: List[RecordedSession],
      branch: Option[String] = None
  ): AttemptRecord =
    val started = Instant.parse(startedAt)
    AttemptRecord(
      orcaVersion = "0.0.test",
      flow = Some("a-flow.sc"),
      workDir = workDir,
      branch = branch.map(branchName),
      pid = pid,
      startedAt = started,
      status = status,
      sessions = sessions
    )

  /** A session minted under `sessionName` in `sessionStage`. */
  def durable(
      agent: String = "main",
      sessionName: String = "main",
      sessionStage: StagePath = StagePath.FlowBody,
      stage: Option[String] = None,
      lastActiveAt: String = "2026-07-18T10:00:00Z",
      backend: BackendTag = BackendTag.ClaudeCode,
      wireId: Option[String] = Some("uuid")
  ): RecordedSession =
    RecordedSession(
      backend = backend,
      wireId = wireId,
      agent = agent,
      role = None,
      stage = stage,
      minted = Some(
        SessionKey(
          name = sessionName,
          stage = sessionStage
        )
      ),
      lastActiveAt = Instant.parse(lastActiveAt)
    )

  def ephemeral(
      agent: String = "main",
      role: Option[String] = None,
      stage: Option[String] = None,
      lastActiveAt: String = "2026-07-18T10:00:00Z",
      backend: BackendTag = BackendTag.ClaudeCode,
      wireId: Option[String] = Some("uuid")
  ): RecordedSession =
    RecordedSession(
      backend = backend,
      wireId = wireId,
      agent = agent,
      role = role,
      stage = stage,
      minted = None,
      lastActiveAt = Instant.parse(lastActiveAt)
    )

  /** `attempt` as [[EventLogReader]] records it: under the attempt id its
    * `startedAt` and `pid` spell.
    */
  def recorded(
      attempt: AttemptRecord,
      observedStatus: ObservedStatus
  ): RecordedAttempt =
    RecordedAttempt(
      AttemptId(attempt.startedAt, attempt.pid),
      attempt,
      observedStatus
    )

  /** [[recorded]] with the writing process still alive. */
  def recorded(attempt: AttemptRecord): RecordedAttempt =
    recorded(attempt, ObservedStatus.of(attempt, _ => true))

  /** `session`, recorded in `attempt`, as a [[SessionIndex]] holds it, with the
    * writing process still alive.
    */
  def selection(
      attempt: AttemptRecord,
      session: RecordedSession
  ): SessionSelection =
    selection(attempt, session, ObservedStatus.of(attempt, _ => true))

  /** `session`, recorded in `attempt`, as a [[SessionIndex]] holds it. */
  def selection(
      attempt: AttemptRecord,
      session: RecordedSession,
      observedStatus: ObservedStatus
  ): SessionSelection =
    SessionSelection(
      SessionRef(
        AttemptId(attempt.startedAt, attempt.pid),
        attempt.sessions.indexOf(session) + 1
      ),
      attempt,
      session,
      observedStatus
    )

  /** Appends the events `attempt` is projected from to the event log of the run
    * keyed `key` under `dir`, as attempt `startedAt`-`pid`. Each session gets
    * its own conversation key, so none merge.
    */
  def writeEventLog(
      dir: os.Path,
      attempt: AttemptRecord,
      key: RunKey = RunKey.of("a prompt")
  ): Unit =
    os.write.append(
      OrcaDir.eventLogPath(dir, key),
      eventsOf(attempt).map(RunEvent.encodeLine(_) + "\n").mkString,
      createFolders = true
    )

  private def eventsOf(m: AttemptRecord): List[RunEvent] =
    val id = AttemptId(m.startedAt, m.pid)
    val started = RunEvent.AttemptStarted(
      m.startedAt,
      id,
      schema = RunEvent.Schema,
      orcaVersion = m.orcaVersion,
      flow = m.flow,
      workDir = m.workDir,
      pid = m.pid,
      trace = None
    )
    val branch = m.branch.map: b =>
      RunEvent.BranchBound(m.startedAt, id, b)
    val commits = m.sessions.zipWithIndex.map: (s, i) =>
      RunEvent.SessionCommitted(
        s.lastActiveAt,
        id,
        backend = s.backend,
        wireId = s.wireId,
        conversationKey = s"conversation-$i",
        agent = s.agent,
        role = s.role,
        minted = s.minted,
        stage = s.stage.map(StagePath.FlowBody.child(_, 0))
      )
    val finished = outcomeOf(m.status).map: outcome =>
      RunEvent.AttemptFinished(m.startedAt, id, outcome)
    (started :: branch.toList) ++ commits ++ finished

  private def outcomeOf(status: AttemptStatus): Option[AttemptOutcome] =
    status match
      case AttemptStatus.Running   => None
      case AttemptStatus.Succeeded => Some(AttemptOutcome.Succeeded)
      case AttemptStatus.Failed    => Some(AttemptOutcome.Failed)
