package orca

import orca.llm.{BackendTag, LlmTool, SessionId}
import orca.progress.SessionRecord

/** Get-or-create session extension for `LlmTool`. Lives in the `flow` module so it
  * can depend on [[FlowControl]] (which is in `flow`) while [[LlmTool]] remains
  * in `tools` (which `flow` depends on, not the reverse).
  */
extension [B <: BackendTag](llm: LlmTool[B])
  /** Get-or-create a session keyed by call-occurrence in this run's log.
    *
    * Reserves/returns a [[SessionId]] and records `(id, seed)` in the progress
    * log; the backend conversation is created lazily on the first gated `run`.
    * On resume, returns the id recorded at this occurrence (does not mint a
    * second). The seed is only recorded here — applying it on first use and
    * replaying it on loss are separate later tasks.
    *
    * No LLM call and no commit — so it is callable outside a stage. (The id is a
    * fresh UUID, so it is not referentially transparent.) The store write uses a
    * runtime-minted `InStage.unsafe` (the same pattern the `stage` runtime uses
    * for setup-phase mutations).
    */
  def session(seed: String)(using fc: FlowControl): SessionId[B] =
    val idx = fc.nextSessionOccurrence()
    fc.progressStore.load().flatMap(_.sessions.find(_.index == idx)) match
      case Some(recorded) =>
        // Resume path: return the recorded session id without minting a new one.
        SessionId[B](recorded.id)
      case None =>
        // First run: mint a fresh id, record it, and return it.
        val freshId = SessionId.fresh[B]
        given InStage = InStage.unsafe
        fc.progressStore.upsertSession(
          SessionRecord(index = idx, id = freshId.value, seed = seed)
        )
        freshId
