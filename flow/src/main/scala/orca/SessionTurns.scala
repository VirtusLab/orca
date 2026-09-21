package orca

/** Where a turn sits in this run's use of one durable conversation. Minted by
  * [[SessionTurns.claimTurn]].
  */
private[orca] enum SessionTurn:
  /** The run has not driven this conversation before. Only here can the
    * conversation's memory predate the run — and so disagree with a working
    * tree the run started from.
    */
  case First

  /** The run has already driven this conversation, so everything it remembers
    * doing, it did here.
    */
  case Later

/** Per-run record of which durable conversations this run has already driven,
  * shared by every [[FlowControl]] implementation so a test double answers as
  * production does.
  *
  * Separate from [[StageFrames]] because this is not stage identity: a
  * conversation is addressed by its session id, outlives the stage that minted
  * it, and is driven by both durable doors.
  */
private[orca] trait SessionTurns:
  // A concurrent set rather than a var, as `EnforcementNotice` does for its own
  // say-once bookkeeping; the durable run doors reach this only after their
  // owner-thread assert.
  private val driven =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** Claim `sessionId`'s next turn: [[SessionTurn.First]] exactly once per
    * conversation per run, [[SessionTurn.Later]] after that.
    *
    * Claimed on every turn, whatever the turn then does with the answer — a
    * conversation opened by this run's own first turn must not read as first
    * again on its second.
    */
  private[orca] def claimTurn(sessionId: String): SessionTurn =
    if driven.add(sessionId) then SessionTurn.First else SessionTurn.Later
