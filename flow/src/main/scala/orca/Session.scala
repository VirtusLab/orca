package orca

import orca.agents.{
  BackendTag,
  Agent,
  SessionId,
  AgentInput,
  Announce,
  JsonData
}
import orca.events.OrcaEvent
import orca.progress.{ProgressLog, SessionRecord}

/** A durable, resumable LLM-session handle — the single door for sessions that
  * must survive a flow crash and resume. Obtain one with `agent.session(name,
  * seed)`; it bundles the [[Agent]] with the reserved [[SessionId]] and owns
  * the entire probe → seed/preamble → run → persist protocol, so a durable run
  * can never silently skip seeding or wire-id persistence the way a raw
  * `agent.autonomous.run(prompt, session)` door does.
  *
  * Use [[run]] for free-form text and [[resultAs]]`.run` for a structured `O`.
  * Both prime the conversation with the recorded seed and a progress preamble
  * when the backend conversation isn't live (first use or lost on resume), then
  * persist the backend's learned resume wire id — mirror images of the same
  * protocol on the two doors.
  *
  * '''Escape hatch:''' [[id]] exposes the underlying [[SessionId]]. Passing it
  * to a raw `agent.autonomous.run` / `agent.resultAs[O]...run` /
  * `agent.interactive.run(input, session.id)` door — interactive included,
  * since it is deliberately not offered as a durable door here — FORFEITS
  * seeding and wire-id persistence: those doors are in-run only and never write
  * back to the log, so on crash/resume the durable side finds nothing recorded
  * and rehydration opens a brand-new, unseeded session rather than continuing
  * the old one.
  *
  * The handle is a plain immutable value ([[Agent]] + [[SessionId]]): mint it
  * once (outside/before stages) and freely close over it into any later
  * `stage(...)`. Only the capabilities its methods require ([[InStage]],
  * [[WorkspaceWrite]]) are stage-scoped — the handle itself carries no stage
  * affinity.
  */
final class FlowSession[B <: BackendTag] private[orca] (
    agent: Agent[B],
    /** The underlying reserved session id — the documented escape hatch (see
      * the class scaladoc). Prefer [[run]] / [[resultAs]]; reach for `.id` only
      * to hand the raw doors an ephemeral continuation.
      */
    val id: SessionId[B]
):

  /** Run the agent autonomously against this session on free-form `prompt`,
    * priming it with the recorded seed + a progress preamble IF the backend
    * conversation isn't live (a fresh first use, or lost on resume). If the
    * session is live, runs `prompt` as-is (continues the conversation). Returns
    * the run's output.
    *
    * The seed is looked up from the progress log by matching [[id]]; if no
    * record is found the seed is treated as empty (does not throw). The
    * progress preamble names completed stages and is only included when there
    * is at least one completed entry — a true first use gets just `seed +
    * prompt`, with no misleading "resuming" text.
    *
    * The [[WorkspaceWrite]] token is supplied explicitly (not self-minted):
    * inside a stage body it is already ambient, so this costs the caller
    * nothing, while making "durable runs are flow-thread-only, never from a
    * `fork`" a signature-level fact (ADR 0018 §6).
    */
  def run(prompt: String)(using
      fc: FlowControl,
      ev: InStage,
      ws: WorkspaceWrite
  ): String =
    val (_, output) =
      agent.autonomous.run(effectivePrompt(agent, id, prompt), id)
    persistResumeWireId(agent, id)
    output

  /** Structured (`resultAs[O]`) durable door. Fixes the output type and yields
    * a gateway whose `run(input)` applies the same probe → seed/preamble → run
    * → persist protocol as [[run]] to the structured call (see
    * [[FlowSessionCall]]).
    */
  def resultAs[O: JsonData: Announce]: FlowSessionCall[B, O] =
    new FlowSessionCall(agent, id)

/** Structured-durable gateway for a [[FlowSession]] (obtained via
  * [[FlowSession.resultAs]]). Mirrors the raw `agent.resultAs[O]` gateway's `O`
  * fixing, but — unlike that gateway — exposes a single `run`, not an
  * `autonomous`/`interactive` split: interactive durable sessions are
  * deliberately not offered (see the [[FlowSession]] class scaladoc), so there
  * is no sibling mode for `autonomous` to distinguish itself from.
  */
final class FlowSessionCall[B <: BackendTag, O] private[orca] (
    agent: Agent[B],
    id: SessionId[B]
)(using JsonData[O], Announce[O]):

  /** Autonomous structured turn against the durable session. Applies the same
    * seed/probe/persist protocol as [[FlowSession.run]]: on a lost/fresh
    * session it prepends the recorded seed + progress preamble to the
    * serialized `input`, runs the structured `resultAs[O].autonomous` door,
    * then persists the learned resume wire id.
    *
    * `emitPrompt` has no default-driving asymmetry with [[FlowSession.run]]'s
    * free-text prompt beyond its existence: a free-text prompt is the caller's
    * own authored text, so it is always worth emitting as a `UserPrompt` event,
    * while a structured `input` is serialized to (potentially large) JSON, so
    * callers that produce near-identical inputs in quick succession (e.g. a
    * per-task fix turn) can pass `false` to suppress it.
    */
  def run[I](input: I, emitPrompt: Boolean = true)(using
      fc: FlowControl,
      ai: AgentInput[I],
      ev: InStage,
      ws: WorkspaceWrite
  ): O =
    val serialized = ai.serialize(input)
    val (_, output) = agent
      .resultAs[O]
      .autonomous
      .run(effectivePrompt(agent, id, serialized), id, emitPrompt = emitPrompt)
    persistResumeWireId(agent, id)
    output

/** Get-or-create session extension for `Agent`. Lives in the `flow` module so
  * it can depend on [[FlowControl]] (which is in `flow`) while [[Agent]]
  * remains in `tools` (which `flow` depends on, not the reverse).
  */
extension [B <: BackendTag](agent: Agent[B])
  /** Get-or-create a durable [[FlowSession]] keyed by `name` + call-occurrence
    * in this run's log, stage-style (mirrors `stage(name)`'s id, ADR 0018
    * §2.1).
    *
    * Reserves a [[SessionId]] and records `(name, occurrence, id, seed)` in the
    * progress log, then returns a [[FlowSession]] handle wrapping it; the
    * backend conversation is created lazily on the handle's first gated `run`.
    * On resume, wraps the id recorded at this `(name, occurrence)`, matching
    * `fc.nextSessionOccurrence(name)` against the same-named calls so far in
    * this run (does not mint a second). The seed is only recorded here;
    * [[FlowSession.run]] applies it on first use and replays it on loss.
    *
    * Because the key is `name` + occurrence rather than call position, identity
    * survives inserting/reordering *other* `session(...)` calls between runs —
    * only the call order among calls sharing this `name` matters for
    * disambiguating duplicates.
    *
    * No LLM call and no commit — so it is callable outside a stage. (The id is
    * a fresh UUID, so it is not referentially transparent.) The store write
    * mints its [[WorkspaceWrite]] token via [[RuntimeInStage]] (the same door
    * the `stage` runtime uses for setup-phase mutations) — this is the one call
    * in the family that must remain outside-stage-callable, so it self-mints;
    * [[FlowSession.run]]/[[FlowSessionCall]] instead take the token explicitly.
    * Because that write isn't committed, a failure teardown's `git reset
    * --hard` can erase it before the next stage commit carries the log — the
    * retry then mints a fresh session and re-seeds (see
    * `ProgressStore.upsertSession`).
    */
  def session(name: String, seed: String)(using
      fc: FlowControl
  ): FlowSession[B] =
    // An empty name would collide with legacy (pre-naming) records that
    // decode to name="" — that's an authoring defect, so require rather than
    // return an Either.
    require(name.nonEmpty, "session name must be non-empty")
    val occ = fc.nextSessionOccurrence(name)
    val id = fc.progressStore
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
    new FlowSession(agent, id)

/** Probe → prime step shared by [[FlowSession.run]] and
  * [[FlowSessionCall.run]]: if the backend conversation for `session` is live,
  * `text` is returned verbatim (continues the conversation); otherwise the
  * recorded seed and progress preamble are looked up and prepended, per the
  * class scaladoc's probe → seed/preamble → run → persist protocol. Persisting
  * the learned wire id afterward is each caller's own last step (see
  * [[persistResumeWireId]]) — only the probe/prime half is common, since the
  * two doors run different underlying calls with the primed text.
  */
private def effectivePrompt[B <: BackendTag](
    agent: Agent[B],
    session: SessionId[B],
    text: String
)(using fc: FlowControl): String =
  if agent.sessionExists(session) then text
  else
    val log = fc.progressStore.load()
    val seed = lookupSeed(log, session)
    val preamble = progressPreamble(log)
    composePrimedPrompt(preamble, seed, text)

/** After a run, persist the wire id to resume against that the backend has now
  * learned (durable backends only — pi returns `None`), so a resumed run can
  * rehydrate the map and continue/probe the right session. Upserts the matching
  * [[SessionRecord]] only when the learned wire id differs from what is already
  * recorded (and a record for `session` exists), so a no-op run writes nothing.
  * Takes the [[WorkspaceWrite]] token explicitly — its callers
  * ([[FlowSession.run]] / [[FlowSessionCall]]) run inside a stage where the
  * token is ambient, and requiring it keeps these persisting writes
  * flow-thread-only (ADR 0018 §6).
  */
private def persistResumeWireId[B <: BackendTag](
    agent: Agent[B],
    session: SessionId[B]
)(using fc: FlowControl, ws: WorkspaceWrite): Unit =
  agent
    .resumeWireId(session)
    .foreach: wireId =>
      val log = fc.progressStore.load()
      log
        .flatMap(_.sessions.find(_.id == session.value))
        .foreach: record =>
          if !record.resumeWireId.contains(wireId.value) then
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
