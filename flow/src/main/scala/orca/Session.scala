package orca

import orca.agents.{
  AgentCall,
  BackendTag,
  Agent,
  SessionId,
  AgentInput,
  Announce,
  JsonData
}
import orca.events.OrcaEvent
import orca.progress.{ProgressLog, SessionRecord}

import scala.annotation.implicitNotFound
import scala.util.NotGiven

/** Compile-time evidence that no [[InStage]] capability is in scope — i.e. the
  * call site is lexically outside a `stage(...)` body. Lexical only: a helper
  * taking just `(using FlowControl)` that mints inside a stage still compiles,
  * so `agent.session`'s runtime in-stage check remains the backstop.
  */
@implicitNotFound(
  "agent.session(...) must be called outside a stage: mint sessions at the " +
    "flow-body top level, before stages, and run them inside stages via the " +
    "FlowSession handle (session.run / session.resultAs[...].run)."
)
final class OutsideStage private ()
object OutsideStage:
  given (using NotGiven[InStage]): OutsideStage = new OutsideStage

/** A durable, resumable LLM-session handle — the single door for sessions that
  * must survive a flow crash and resume. Obtain one with `agent.session(name,
  * seed)`. It owns the probe → seed/preamble → run → persist protocol, so a
  * durable run can never silently skip seeding or wire-id persistence the way a
  * bare `agent.run` / `chat.run` turn does.
  *
  * Use [[run]] for free-form text and [[resultAs]]`.run` for a structured `O`.
  * Both prime the conversation with the recorded seed and a progress preamble
  * when the backend conversation isn't live (first use or lost on resume), then
  * persist the backend's learned resume wire id.
  *
  * '''Escape hatch:''' [[id]] exposes the underlying [[SessionId]];
  * `agent.chat(session.id)` adopts it as an EPHEMERAL [[orca.agents.Chat]] —
  * the way to continue this conversation from inside a fork (where the doors
  * here are banned), interactive turns included. Chat turns forfeit seeding and
  * wire-id persistence: they are in-run only and never write back to the log,
  * so on crash/resume the durable side finds nothing recorded for them.
  *
  * The handle is a plain immutable value: mint it once at the flow-body top
  * level (minting inside a stage is rejected — see `agent.session`) and close
  * over it into any later `stage(...)`. Only the capabilities its methods
  * require ([[InStage]], [[WorkspaceWrite]]) are stage-scoped — the handle
  * itself carries no stage affinity.
  */
final class FlowSession[B <: BackendTag] private[orca] (
    private[orca] val agent: Agent[B],
    /** The underlying reserved session id — the escape hatch (see the class
      * scaladoc). Prefer [[run]] / [[resultAs]]; reach for `.id` only to mint
      * an ephemeral continuation via `agent.chat(id)`.
      */
    val id: SessionId[B]
):

  /** Run the agent autonomously against this session on free-form `prompt`,
    * priming it with the recorded seed + a progress preamble if the backend
    * conversation isn't live (fresh first use, or lost on resume); otherwise
    * runs `prompt` as-is. Returns the run's output.
    *
    * The seed is looked up from the progress log by matching [[id]]; a missing
    * record is treated as an empty seed (does not throw). The preamble names
    * completed stages and is included only when there is at least one, so a
    * true first use gets just `seed + prompt` with no misleading "resuming"
    * text.
    *
    * The [[WorkspaceWrite]] token is taken explicitly rather than self-minted,
    * making "durable runs are flow-thread-only, never from a `fork`" a
    * signature-level fact (ADR 0018 §6); inside a stage it is already ambient.
    */
  def run(prompt: String)(using
      fc: FlowControl,
      ev: InStage,
      ws: WorkspaceWrite
  ): String =
    fc.assertOwnerThread("session.run(...)")
    val output = agent.autonomous
      .runWithSession(effectivePrompt(agent, id, prompt), id, None, true)
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
  * [[FlowSession.resultAs]]). Fixes the output type `O`, and exposes a single
  * `run` (no `autonomous`/`interactive` split): interactive durable sessions
  * are deliberately not offered — see the [[FlowSession]] class scaladoc.
  */
final class FlowSessionCall[B <: BackendTag, O] private[orca] (
    agent: Agent[B],
    id: SessionId[B]
)(using JsonData[O], Announce[O]):

  /** Held as a val so schema derivation (`JsonSchemaGen`) fails fast at
    * construction time — matching `agent.resultAs[O]` — instead of on the first
    * `run()` after stage work has already started.
    */
  private val call: AgentCall[B, O] = agent.resultAs[O]

  /** Autonomous structured turn against the durable session. Applies the same
    * seed/probe/persist protocol as [[FlowSession.run]] to the serialized
    * `input`.
    *
    * `emitPrompt` gates the `UserPrompt` event: a structured `input` serializes
    * to (potentially large) JSON, so callers producing near-identical inputs in
    * quick succession (e.g. a per-task fix turn) can pass `false` to suppress
    * it.
    */
  def run[I](input: I, emitPrompt: Boolean = true)(using
      fc: FlowControl,
      ai: AgentInput[I],
      ev: InStage,
      ws: WorkspaceWrite
  ): O =
    fc.assertOwnerThread("session.run(...)")
    val serialized = ai.serialize(input)
    val output = call.autonomous
      .runWithSession(
        effectivePrompt(agent, id, serialized),
        id,
        None,
        emitPrompt
      )
    persistResumeWireId(agent, id)
    output

/** Get-or-create session extension for `Agent`. Lives in the `flow` module so
  * it can depend on [[FlowControl]] while [[Agent]] stays in `tools`.
  */
extension [B <: BackendTag](agent: Agent[B])
  /** Get-or-create a durable [[FlowSession]] keyed by `name` + call-occurrence
    * in this run's log, stage-style (mirrors `stage(name)`'s id, ADR 0018
    * §2.1).
    *
    * Reserves a [[SessionId]] and records `(name, occurrence, id, seed)` in the
    * progress log, then returns a [[FlowSession]] wrapping it; the backend
    * conversation is created lazily on the handle's first gated `run`. On
    * resume, wraps the id recorded at this `(name, occurrence)` rather than
    * minting a second. The seed is only recorded here; [[FlowSession.run]]
    * applies it on first use and replays it on loss.
    *
    * Keying by `name` + occurrence (not call position) means identity survives
    * inserting/reordering *other* `session(...)` calls between runs; only the
    * order among calls sharing this `name` matters.
    *
    * No LLM call and no commit, so it is callable outside a stage (and, minting
    * a fresh UUID, is not referentially transparent). This is the one call in
    * the family that must remain outside-stage-callable, so its store write
    * self-mints a [[WorkspaceWrite]] via [[RuntimeInStage]] rather than taking
    * the token explicitly. Because that write isn't committed, a failure
    * teardown's `git reset --hard` can erase it before the next stage commit
    * carries the log — the retry then mints a fresh session and re-seeds (see
    * `ProgressStore.upsertSession`).
    */
  def session(name: String, seed: String)(using
      fc: FlowControl,
      outside: OutsideStage
  ): FlowSession[B] =
    // An empty name decodes ambiguously; treat it as an authoring defect.
    require(name.nonEmpty, "session name must be non-empty")
    // A session minted inside a stage that gets skipped on resume would never
    // re-mint, desyncing the occurrence counter — so require the flow-body top
    // level (ADR 0018 §2.6). [[OutsideStage]] rejects the direct in-stage call
    // at compile time; this catches the indirect path it can't see (a
    // FlowControl-only helper invoked from within a stage).
    if fc.inStage then
      throw new OrcaFlowException(
        "agent.session(...) must be called outside a stage: mint sessions at " +
          "the flow-body top level, before stages, and run them inside stages " +
          "via the FlowSession handle (session.run / session.resultAs[...].run)."
      )
    val occ = fc.nextSessionOccurrence(name)
    val id = fc.progressStore
      .load()
      .flatMap(
        _.sessions.find(r => r.name == name && r.occurrence == occ)
      ) match
      case Some(recorded) =>
        val currentTag = agent.backendTag.map(_.wireName)
        recorded.backend match
          case Some(recordedTag) if currentTag != Some(recordedTag) =>
            // Backend swapped between runs: `recorded.id` is meaningful only in
            // the old backend's registry, so mint fresh rather than reuse it.
            // Checked before the seed-diff warning below so a tag mismatch
            // surfaces the ONLY warning — a seed-diff warning here would falsely
            // claim the (never-applied) edited seed is being reused.
            fc.emit(
              OrcaEvent.Step(
                s"warning: session '$name' #$occ was minted on " +
                  s"$recordedTag; this agent is " +
                  s"${currentTag.getOrElse("untagged")} — minting fresh"
              )
            )
            mintSession(agent, name, occ, seed)
          case _ =>
            // Tags match (or the record predates tagging). The recorded id is
            // log-sourced and untrusted: parse it rather than resume against a
            // value that could carry a path/regex/URL injection downstream; a
            // parse failure mints fresh like the tag-mismatch case.
            SessionId.parse[B](recorded.id) match
              case Some(validId) =>
                // The only branch that genuinely reuses the recorded session,
                // so the only one where a seed-diff warning is honest. A
                // recorded seed differing from this call's means the seed was
                // edited between runs; the session is reused either way (re-seed
                // is the safe fallback, ADR 0018 §2.6) — surface the divergence
                // rather than resume silently.
                if recorded.seed != seed then
                  fc.emit(
                    OrcaEvent.Step(
                      s"warning: session '$name' #$occ recorded seed differs " +
                        "for this name — the seed was edited; reusing the " +
                        "recorded session"
                    )
                  )
                validId
              case None =>
                fc.emit(
                  OrcaEvent.Step(
                    s"warning: session '$name' #$occ has an invalid recorded " +
                      "id — minting fresh"
                  )
                )
                mintSession(agent, name, occ, seed)
      case None =>
        // First run.
        mintSession(agent, name, occ, seed)
    new FlowSession(agent, id)

/** Mint a fresh session id, record `(name, occurrence, id, seed, backend)` in
  * the progress log (replacing any existing record at the same key — see
  * `ProgressStore.upsertSession`), and return it. Shared by every arm of
  * `agent.session(name, seed)`'s reuse match that must not trust a
  * stale/mismatched/corrupt recorded id. Mints its own [[WorkspaceWrite]] via
  * [[RuntimeInStage]] — see `session`'s scaladoc.
  */
private def mintSession[B <: BackendTag](
    agent: Agent[B],
    name: String,
    occurrence: Int,
    seed: String
)(using fc: FlowControl): SessionId[B] =
  val freshId = SessionId.fresh[B]
  given WorkspaceWrite = RuntimeInStage.workspaceToken()
  fc.progressStore.upsertSession(
    SessionRecord(
      name = name,
      occurrence = occurrence,
      id = freshId.value,
      seed = seed,
      backend = agent.backendTag.map(_.wireName)
    )
  )
  freshId

/** Probe → prime step shared by [[FlowSession.run]] and
  * [[FlowSessionCall.run]]: if the backend conversation for `session` is live,
  * `text` is returned verbatim; otherwise the recorded seed and progress
  * preamble are prepended. Persisting the learned wire id afterward is each
  * caller's own last step (see [[persistResumeWireId]]), since the two doors
  * run different underlying calls.
  */
private def effectivePrompt[B <: BackendTag](
    agent: Agent[B],
    session: SessionId[B],
    text: String
)(using fc: FlowControl): String =
  if agent.willContinue(session) then text
  else
    val log = fc.progressStore.load()
    val record = log.flatMap(_.sessions.find(_.id == session.value))
    // A recorded wire id proves a backend conversation once existed, so a
    // failed probe here means it was lost (pruned store, another machine): the
    // rebuilt conversation gets only seed + preamble, not the prior turns.
    // Surface that — silently degraded context is hard to debug. A record
    // without a wire id is a plain first use, no warning.
    if record.exists(_.resumeWireId.isDefined) then
      fc.emit(
        OrcaEvent.Step(
          s"warning: session '${record.fold("?")(_.name)}' — backend " +
            "conversation not found; re-seeding (prior conversation history " +
            "is lost)"
        )
      )
    val seed = record.map(_.seed).filter(_.nonEmpty)
    val preamble = progressPreamble(log)
    composePrimedPrompt(preamble, seed, text)

/** After a run, persist the backend's now-learned resume wire id (durable
  * backends only — pi returns `None`), so a resumed run can rehydrate the map
  * and probe the right session. Also self-heals [[SessionRecord.backend]] from
  * `None` (an untagged record) to `agent`'s current tag, on the very run that
  * just proved this `agent` owns it, rather than waiting for a second
  * `session(...)` call. Upserts only when the learned wire id or the healed tag
  * differs from what is recorded, so a no-op run writes nothing. Takes the
  * [[WorkspaceWrite]] token explicitly to keep these writes flow-thread-only
  * (ADR 0018 §6).
  */
private def persistResumeWireId[B <: BackendTag](
    agent: Agent[B],
    session: SessionId[B]
)(using fc: FlowControl, ws: WorkspaceWrite): Unit =
  val healedTag = agent.backendTag.map(_.wireName)
  for
    wireId <- agent.resumeWireId(session)
    log <- fc.progressStore.load()
    record <- log.sessions.find(_.id == session.value)
    if !record.resumeWireId.contains(
      wireId.value
    ) || record.backend != healedTag
  do
    fc.progressStore.upsertSession(
      record.copy(resumeWireId = Some(wireId.value), backend = healedTag)
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
