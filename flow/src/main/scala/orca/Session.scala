package orca

import orca.agents.{BackendTag, Agent, SessionId}
import orca.events.OrcaEvent
import orca.progress.{ProgressLog, SessionRecord}

/** Get-or-create session extension for `Agent`. Lives in the `flow` module so
  * it can depend on [[FlowControl]] (which is in `flow`) while [[Agent]]
  * remains in `tools` (which `flow` depends on, not the reverse).
  */
extension [B <: BackendTag](agent: Agent[B])
  /** Get-or-create a session keyed by `name` + call-occurrence in this run's
    * log, stage-style (mirrors `stage(name)`'s id, ADR 0018 §2.1).
    *
    * Reserves/returns a [[SessionId]] and records `(name, occurrence, id,
    * seed)` in the progress log; the backend conversation is created lazily on
    * the first gated `run`. On resume, returns the id recorded at this `(name,
    * occurrence)`, matching `fc.nextSessionOccurrence(name)` against the
    * same-named calls so far in this run (does not mint a second). The seed is
    * only recorded here — applying it on first use and replaying it on loss are
    * separate later tasks.
    *
    * Because the key is `name` + occurrence rather than call position, identity
    * survives inserting/reordering *other* `session(...)` calls between runs —
    * only the call order among calls sharing this `name` matters for
    * disambiguating duplicates.
    *
    * No LLM call and no commit — so it is callable outside a stage. (The id is
    * a fresh UUID, so it is not referentially transparent.) The store write
    * mints its [[WorkspaceWrite]] token via [[RuntimeInStage]] (the same door
    * the `stage` runtime uses for setup-phase mutations). Because that write
    * isn't committed, a failure teardown's `git reset --hard` can erase it
    * before the next stage commit carries the log — the retry then mints a
    * fresh session and re-seeds (see `ProgressStore.upsertSession`).
    */
  def session(name: String, seed: String)(using fc: FlowControl): SessionId[B] =
    // An empty name would collide with legacy (pre-naming) records that
    // decode to name="" — that's an authoring defect, so require rather than
    // return an Either.
    require(name.nonEmpty, "session name must be non-empty")
    val occ = fc.nextSessionOccurrence(name)
    fc.progressStore
      .load()
      .flatMap(
        _.sessions.find(r => r.name == name && r.occurrence == occ)
      ) match
      case Some(recorded) =>
        // Sessions are keyed by name + occurrence (see the scaladoc), so a
        // recorded seed differing from this call's means the seed was edited
        // between runs. The recorded session is reused either way (re-seed is
        // the safe fallback, ADR 0018 §2.6) — surface the divergence rather
        // than resume the wrong session silently.
        if recorded.seed != seed then
          fc.emit(
            OrcaEvent.Step(
              s"warning: session '$name' #$occ recorded seed differs for " +
                "this name — the seed was edited; reusing the recorded session"
            )
          )
        SessionId[B](recorded.id)
      case None =>
        // First run: mint a fresh id, record it, and return it.
        val freshId = SessionId.fresh[B]
        given WorkspaceWrite = RuntimeInStage.workspaceToken()
        fc.progressStore.upsertSession(
          SessionRecord(
            name = name,
            occurrence = occ,
            id = freshId.value,
            seed = seed,
            backend = agent.backendTag.map(_.toString)
          )
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
    persistResumeWireId(agent, session)
    result

/** After a run, persist the wire id to resume against that the backend has now
  * learned (durable backends only — pi returns `None`), so a resumed run can
  * rehydrate the map and continue/probe the right session. Upserts the matching
  * [[SessionRecord]] only when the learned wire id differs from what is already
  * recorded (and a record for `session` exists), so a no-op run writes nothing.
  * The store write mints its [[WorkspaceWrite]] token via [[RuntimeInStage]],
  * the same pattern [[session]] uses for the setup-phase write.
  */
private def persistResumeWireId[B <: BackendTag](
    agent: Agent[B],
    session: SessionId[B]
)(using fc: FlowControl): Unit =
  agent
    .resumeWireId(session)
    .foreach: wireId =>
      val log = fc.progressStore.load()
      log
        .flatMap(_.sessions.find(_.id == session.value))
        .foreach: record =>
          if !record.resumeWireId.contains(wireId.value) then
            given WorkspaceWrite = RuntimeInStage.workspaceToken()
            fc.progressStore.upsertSession(
              record.copy(resumeWireId = Some(wireId.value))
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
