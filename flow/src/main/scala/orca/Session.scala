package orca

import orca.agents.{BackendTag, Agent, SessionId}
import orca.progress.{ProgressLog, SessionRecord}

/** Get-or-create session extension for `Agent`. Lives in the `flow` module so
  * it can depend on [[FlowControl]] (which is in `flow`) while [[Agent]]
  * remains in `tools` (which `flow` depends on, not the reverse).
  */
extension [B <: BackendTag](agent: Agent[B])
  /** Get-or-create a session keyed by call-occurrence in this run's log.
    *
    * Reserves/returns a [[SessionId]] and records `(id, seed)` in the progress
    * log; the backend conversation is created lazily on the first gated `run`.
    * On resume, returns the id recorded at this occurrence (does not mint a
    * second). The seed is only recorded here — applying it on first use and
    * replaying it on loss are separate later tasks.
    *
    * The key is **positional** (the n-th `session(...)` call in the run), like
    * `stage`'s id. So across runs, keep `session(...)` calls in a stable order
    * and unconditional — reordering them, or skipping one on some runs, shifts
    * every later session's identity and loses resume continuity.
    *
    * No LLM call and no commit — so it is callable outside a stage. (The id is
    * a fresh UUID, so it is not referentially transparent.) The store write
    * uses a runtime-minted `InStage.unsafe` (the same pattern the `stage`
    * runtime uses for setup-phase mutations).
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

  /** Run the agent autonomously against `session`, priming it with the recorded
    * seed + a progress preamble IF the backend conversation isn't live (a fresh
    * first use, or lost on resume). If the session is live, runs `prompt` as-is
    * (continues the conversation). Returns the run's (SessionId, output).
    *
    * The seed is looked up from the progress log by matching `session`'s id; if
    * no record is found the seed is treated as empty (does not throw).
    *
    * The progress preamble names completed stages and is only included when
    * there is at least one completed entry — a true first use gets just `seed +
    * prompt`, with no misleading "resuming" text.
    */
  def runSeeded(
      prompt: String,
      session: SessionId[B]
  )(using fc: FlowControl, ev: InStage): (SessionId[B], String) =
    val result =
      if agent.sessionExists(session) then agent.autonomous.run(prompt, session)
      else
        val log = fc.progressStore.load()
        val seed = lookupSeed(log, session)
        val preamble = progressPreamble(log)
        val primedPrompt = composePrimedPrompt(preamble, seed, prompt)
        agent.autonomous.run(primedPrompt, session)
    persistServerId(agent, session)
    result

/** After a run, persist the server-side session id the backend has now learned
  * (server-id backends only — claude/pi return `None`), so a resumed run can
  * rehydrate the client→server map and continue/probe the right server thread.
  * Upserts the matching [[SessionRecord]] only when the learned server id
  * differs from what is already recorded (and a record for `session` exists),
  * so a no-op run writes nothing. The store write uses a runtime-minted
  * `InStage.unsafe`, the same pattern [[session]] uses for the setup-phase
  * write.
  */
private def persistServerId[B <: BackendTag](
    agent: Agent[B],
    session: SessionId[B]
)(using fc: FlowControl): Unit =
  agent
    .serverSessionId(session)
    .foreach: server =>
      val log = fc.progressStore.load()
      log
        .flatMap(_.sessions.find(_.id == session.value))
        .foreach: record =>
          if !record.serverId.contains(server.value) then
            given InStage = InStage.unsafe
            fc.progressStore.upsertSession(
              record.copy(serverId = Some(server.value))
            )

/** Look up the recorded seed for `session` from the log. Returns `None` if the
  * log is absent, no record matches `session`, or the recorded seed is empty.
  */
private def lookupSeed[B <: BackendTag](
    log: Option[ProgressLog],
    session: SessionId[B]
): Option[String] =
  log.flatMap(
    _.sessions.find(_.id == session.value).map(_.seed).filter(_.nonEmpty)
  )

/** Compose the progress preamble from completed stage names in the log. Returns
  * `None` if there are no completed entries (first run).
  */
private def progressPreamble(log: Option[ProgressLog]): Option[String] =
  val completed = log.map(_.entries.map(_.name)).getOrElse(Nil)
  if completed.isEmpty then None
  else
    Some(
      s"Progress so far: completed ${completed.mkString(", ")}. Continue from here."
    )

/** Assemble the final primed prompt from the optional preamble, optional seed,
  * and the caller's prompt, omitting absent parts cleanly. The `---` separator
  * appears ONLY when there is a non-empty context (preamble or seed); when
  * neither is present the prompt is returned verbatim.
  */
private def composePrimedPrompt(
    preamble: Option[String],
    seed: Option[String],
    prompt: String
): String =
  val context = List(preamble, seed).flatten.filter(_.nonEmpty).mkString("\n\n")
  if context.isEmpty then prompt else s"$context\n\n---\n\n$prompt"
