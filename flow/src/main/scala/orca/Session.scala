package orca

import orca.agents.{
  AgentCall,
  BackendTag,
  Agent,
  SessionId,
  SessionKey,
  AgentInput,
  Announce,
  JsonData
}
import orca.backend.{Dispatch, ResumeOrigin}
import orca.events.OrcaEvent
import orca.gitref.CommitHash
import orca.progress.ProgressLog
import orca.sessions.SessionRecord
import orca.util.PromptResource

/** A durable, resumable LLM-session handle — the single door for sessions that
  * must survive a flow crash and resume. Obtain one with `agent.session(name,
  * seed)`. It owns the probe → seed/preamble → run → persist protocol, so a
  * durable run can never silently skip seeding or wire-id persistence the way a
  * bare `agent.run` / `chat.run` turn does.
  *
  * Use [[run]] for free-form text and [[resultAs]]`.run` for a structured `O`.
  * Both prime the conversation with the recorded seed and a progress preamble
  * when the backend conversation isn't live (first use or lost on resume), and
  * with the interrupted-attempt notice when it IS live but a previous run
  * opened it, then persist the backend's learned resume wire id.
  *
  * '''Escape hatch:''' [[id]] exposes the underlying [[SessionId]];
  * `agent.chat(session.id)` adopts it as an EPHEMERAL [[orca.agents.Chat]] —
  * the way to continue this conversation from inside a fork (where the doors
  * here are banned), interactive turns included. Chat turns forfeit seeding,
  * the interrupted-attempt notice and wire-id persistence: they are in-run only
  * and never write back to the session store, so on crash/resume the durable
  * side finds nothing recorded for them.
  *
  * The handle is a plain immutable value with no stage affinity of its own —
  * only the capabilities its methods require ([[InStage]], [[WorkspaceWrite]])
  * are stage-scoped. It has no `JsonData` instance, and neither does
  * [[SessionId]], so a handle minted inside a `stage(...)` cannot be returned
  * as that stage's result (ADR 0018 §2.6 R22).
  */
final class FlowSession[B <: BackendTag] private[orca] (
    private[orca] val agent: Agent[B],
    /** The underlying reserved session id — the escape hatch (see the class
      * scaladoc). Prefer [[run]] / [[resultAs]]; reach for `.id` only to mint
      * an ephemeral continuation via `agent.chat(id)`.
      */
    val id: SessionId[B],
    /** The key this session was minted under. Carried onto every turn's
      * `OrcaEvent.SessionCommitted`, which is what names the session in the run
      * manifest and tells same-named sessions apart in the shell's picker.
      */
    private[orca] val key: SessionKey
):

  /** Run the agent autonomously against this session on free-form `prompt`,
    * primed as the class scaladoc describes. Returns the run's output.
    *
    * A session store holding no record for [[id]] is an empty seed, not an
    * error.
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
    val output = agent.runText(
      effectivePrompt(agent, id, prompt),
      id,
      sessionKey = Some(key),
      emitPrompt = true
    )
    persistResumeWireId(agent, id)
    output

  /** Structured (`resultAs[O]`) durable door. Fixes the output type and yields
    * a gateway whose `run(input)` applies the same probe → seed/preamble → run
    * → persist protocol as [[run]] to the structured call (see
    * [[FlowSessionCall]]).
    */
  def resultAs[O: JsonData: Announce]: FlowSessionCall[B, O] =
    new FlowSessionCall(agent, id, key)

/** Structured-durable gateway for a [[FlowSession]] (obtained via
  * [[FlowSession.resultAs]]). Fixes the output type `O`, and exposes a single
  * `run` (no `autonomous`/`interactive` split): interactive durable sessions
  * are deliberately not offered — see the [[FlowSession]] class scaladoc.
  */
final class FlowSessionCall[B <: BackendTag, O] private[orca] (
    agent: Agent[B],
    id: SessionId[B],
    key: SessionKey
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
    val serialized = ai.serialize(input)
    val output = call.autonomous
      .runWithSession(
        effectivePrompt(agent, id, serialized),
        id,
        sessionKey = Some(key),
        emitPrompt = emitPrompt
      )
    persistResumeWireId(agent, id)
    output

/** Get-or-create session extension for `Agent`. Lives in the `flow` module so
  * it can depend on [[FlowControl]] while [[Agent]] stays in `tools`.
  */
extension [B <: BackendTag](agent: Agent[B])
  /** Get-or-create a durable [[FlowSession]], keyed in this run's log by `name`
    * and the stage this call sits in (the flow body when it sits outside every
    * stage).
    *
    * `name` is the session's role — `implementer`, `final-fixer` — and is what
    * `orca continue <name>` matches. The stage half means two stages asking for
    * `implementer` get two conversations without either naming the other's
    * work, so a per-task flow needs nothing beyond the loop it already has.
    *
    * The backend conversation is created lazily on the handle's first gated
    * `run`. The seed is only recorded here; [[FlowSession.run]] applies it on
    * first use and replays it on loss.
    *
    * Identity follows the stage, not the call's position within it: inserting,
    * reordering or skipping other `session(...)` calls between runs leaves this
    * one alone. What does re-key it is moving the call into another stage, or
    * renaming the stage it sits in — the new key has nothing recorded, so the
    * session is minted fresh and primed from the seed.
    *
    * Minting one name twice in one stage throws (see
    * [[StageFrames.claimSessionKey]]): two handles driving one conversation is
    * an authoring mistake.
    *
    * '''Scope a session to a unit of work''' — one task, one review stage — not
    * to the whole run. A backend re-sends the conversation whole on every call
    * it makes, so a session spanning the run pays for every earlier task on
    * every later turn. Each per-unit session starts from its seed plus the
    * completed-stage preamble instead, which is what the earlier transcript was
    * carrying.
    *
    * '''Mint it where it is used.''' Inside the `stage(...)` that drives it
    * when one stage owns it — the mint and the turns then share that stage's
    * commit, and a resume that skips the stage skips both. Above the stages
    * when several share one session; the key is then the flow body's, so each
    * of those stages reaches the same session. One route between stages is
    * closed: [[FlowSession]] has no `JsonData`, so a handle cannot be returned
    * as a stage's result. An in-memory holder still compiles, and the read
    * fails on the resume that skips the stage that filled it.
    *
    * No LLM call and no commit, so it is callable outside a stage as well as
    * inside one (and, minting a fresh UUID, is not referentially transparent).
    * Having no ambient token there, its store write self-mints a
    * [[WorkspaceWrite]] via [[RuntimeInStage]]; the record outlives the minting
    * stage either way — see [[orca.sessions.SessionStore]].
    */
  def session(name: String, seed: String)(using
      fc: FlowControl
  ): FlowSession[B] =
    // An empty name decodes ambiguously; treat it as an authoring defect.
    require(name.nonEmpty, "session name must be non-empty")
    val key = fc.claimSessionKey(name)
    new FlowSession(agent, resolveSessionId(agent, key, seed), key)

/** The reuse-or-mint decision behind `agent.session(name, seed)`. See
  * `session`'s scaladoc for the reuse contract each branch upholds.
  */
private def resolveSessionId[B <: BackendTag](
    agent: Agent[B],
    key: SessionKey,
    seed: String
)(using fc: FlowControl): SessionId[B] =
  fc.sessionStore.records().find(_.key == key) match
    case Some(recorded) => reuseOrMint(agent, key, seed, recorded)
    case None           => mintSession(agent, key, seed)

/** Backend-tag mismatch is checked before the seed diff so a swapped backend
  * reports exactly one warning (a seed-diff warning here would falsely claim
  * the never-applied edited seed is being reused).
  */
private def reuseOrMint[B <: BackendTag](
    agent: Agent[B],
    key: SessionKey,
    seed: String,
    recorded: SessionRecord
)(using fc: FlowControl): SessionId[B] =
  recorded.backend match
    case Some(recordedTag) if agent.backendTag != recordedTag =>
      // Backend swapped between runs: `recorded.id` is meaningful only in the
      // old backend's registry, so mint fresh rather than reuse it.
      warnBackendSwap(fc, key, recordedTag, agent.backendTag)
      mintSession(agent, key, seed)
    case _ =>
      // Tags match (or the record predates tagging). The recorded id is
      // log-sourced and untrusted: parse it rather than resume against a
      // value that could carry a path/regex/URL injection downstream; a
      // parse failure mints fresh like the tag-mismatch case.
      SessionId.parse[B](recorded.id) match
        case Some(validId) =>
          // Reuse is the safe fallback (ADR 0018 §2.6): a seed edited
          // between runs is surfaced as a warning, never a re-mint.
          warnIfSeedDiffers(fc, key, recorded.seed, seed)
          validId
        case None =>
          warnInvalidRecordedId(fc, key)
          mintSession(agent, key, seed)

private def warnBackendSwap(
    fc: FlowControl,
    key: SessionKey,
    recordedTag: BackendTag,
    currentTag: BackendTag
): Unit =
  fc.context.emit(
    OrcaEvent.Step(
      s"warning: session ${key.describe} was minted on " +
        s"$recordedTag; this agent is $currentTag — minting fresh"
    )
  )

private def warnIfSeedDiffers(
    fc: FlowControl,
    key: SessionKey,
    recordedSeed: String,
    seed: String
): Unit =
  if recordedSeed != seed then
    fc.context.emit(
      OrcaEvent.Step(
        s"warning: session ${key.describe} recorded seed differs " +
          "for this key — the seed was edited; reusing the recorded session"
      )
    )

private def warnInvalidRecordedId(fc: FlowControl, key: SessionKey): Unit =
  fc.context.emit(
    OrcaEvent.Step(
      s"warning: session ${key.describe} has an invalid recorded id " +
        "— minting fresh"
    )
  )

/** Mints its own [[WorkspaceWrite]] via [[RuntimeInStage]] — see `session`'s
  * scaladoc.
  */
private def mintSession[B <: BackendTag](
    agent: Agent[B],
    key: SessionKey,
    seed: String
)(using fc: FlowControl): SessionId[B] =
  val freshId = SessionId.fresh[B]
  given WorkspaceWrite = RuntimeInStage.workspaceToken()
  fc.sessionStore.upsert(
    SessionRecord(
      name = key.name,
      stage = key.stage,
      id = freshId.value,
      seed = seed,
      resumeWireId = None,
      backend = Some(agent.backendTag)
    )
  )
  freshId

/** Probe → prime step shared by both durable doors. Persisting the learned wire
  * id afterward is each caller's own last step (see [[persistResumeWireId]]),
  * since the two doors run different underlying calls.
  *
  * The turn claim is taken here rather than at each door: both doors reach this
  * exactly once per turn, on either branch, so no turn can run unclaimed and
  * read as first twice.
  */
private def effectivePrompt[B <: BackendTag](
    agent: Agent[B],
    session: SessionId[B],
    text: String
)(using fc: FlowControl): String =
  val turn = fc.claimTurn(session.value)
  agent.dispatchFor(session) match
    case Dispatch.Fresh(_) =>
      rebuiltPrompt(fc.sessionStore.records().find(_.id == session.value), text)
    case Dispatch.Resume(_, origin) => continuedPrompt(origin, turn, text)

/** The prompt for a turn the backend will answer from a conversation it still
  * holds. Only a conversation that predates this run is told its uncommitted
  * work is gone, and only on this run's first turn against it — from the
  * second, the uncommitted edits in the tree are this run's own (ADR 0018 §2.6,
  * carried-over live conversations).
  */
private def continuedPrompt(
    origin: ResumeOrigin,
    turn: SessionTurn,
    text: String
): String =
  (turn, origin) match
    case (SessionTurn.First, ResumeOrigin.EarlierRun) =>
      composePrimedPrompt(Some(InterruptedAttemptNotice), None, text)
    case (SessionTurn.First, ResumeOrigin.ThisRun) | (SessionTurn.Later, _) =>
      text

/** The prompt for a turn against a conversation the backend does not hold — a
  * first use, or one lost since the run that opened it — rebuilt from the
  * recorded seed and the progress preamble.
  */
private def rebuiltPrompt(record: Option[SessionRecord], text: String)(using
    fc: FlowControl
): String =
  // A recorded wire id proves a conversation once existed, so a failed probe
  // means it was lost (pruned store, another machine) and the rebuild drops
  // its prior turns; silently degraded context is hard to debug. No wire id is
  // a plain first use.
  if record.exists(_.resumeWireId.isDefined) then
    fc.context.emit(
      OrcaEvent.Step(
        s"warning: session ${record.fold("'?'")(_.key.describe)} — backend " +
          "conversation not found; re-seeding (prior conversation history " +
          "is lost)"
      )
    )
  val seed = record.map(_.seed).filter(_.nonEmpty)
  val preamble =
    progressPreamble(fc.progressStore.load(), fc.context.git.headCommit())
  composePrimedPrompt(preamble, seed, text)

/** Points at the files rather than ordering a redo: a run killed between stages
  * leaves a fully committed tree, where nothing is missing and the order would
  * be vacuous. The stash is deliberately unmentioned — ADR 0018 §2.6's
  * carried-over-conversations amendment says why.
  */
private val InterruptedAttemptNotice: String =
  PromptResource.load("/orca/prompts/interrupted-attempt.md").strip()

/** After a run, persist the backend's now-learned resume wire id (durable
  * backends only — one without a probe returns `None`), so a resumed run can
  * rehydrate the map and probe the right session. Also self-heals
  * [[SessionRecord.backend]] from `None` (an untagged record) to `agent`'s
  * current tag, on the very run that just proved this `agent` owns it. Upserts
  * only when something differs, so a no-op run writes nothing. Takes the
  * [[WorkspaceWrite]] token explicitly to keep these writes flow-thread-only
  * (ADR 0018 §6).
  */
private def persistResumeWireId[B <: BackendTag](
    agent: Agent[B],
    session: SessionId[B]
)(using fc: FlowControl, ws: WorkspaceWrite): Unit =
  val healedTag: Option[BackendTag] = Some(agent.backendTag)
  for
    wireId <- agent.resumeWireId(session)
    record <- fc.sessionStore.records().find(_.id == session.value)
    if !record.resumeWireId.contains(
      wireId.value
    ) || record.backend != healedTag
  do
    fc.sessionStore.upsert(
      record.copy(resumeWireId = Some(wireId.value), backend = healedTag)
    )

/** Compose the progress preamble from completed stage names in the log and the
  * commit the working tree sits at. `None` when no stage has completed.
  *
  * `headCommit` is passed in rather than read here, so the rendered text is a
  * function of the arguments alone.
  *
  * The wording stays neutral about interruption because the same preamble
  * primes a session first used mid-run, after an earlier stage completed — and
  * it speaks of a stage that did not complete rather than of uncommitted work
  * in general, since a stage's own edits ARE committed at its boundary.
  */
private def progressPreamble(
    log: Option[ProgressLog],
    headCommit: Option[CommitHash]
): Option[String] =
  val completed = log.map(_.entries.map(_.id.name)).getOrElse(Nil)
  Option.when(completed.nonEmpty):
    PromptResource.render(
      ProgressPreambleTemplate,
      "completed" -> completed.mkString(", "),
      // Substituted mid-sentence, so the clause carries its own leading space
      // and is empty when the repo has no commit to name.
      "tree" -> headCommit.fold("")(c =>
        s" The working tree is at commit ${c.value}."
      )
    )

private val ProgressPreambleTemplate: String =
  PromptResource.load("/orca/prompts/progress-preamble.md").strip()

/** Assemble the final primed prompt, omitting absent parts: with neither
  * preamble nor seed the caller's prompt is returned verbatim, with no leading
  * separator.
  */
private def composePrimedPrompt(
    preamble: Option[String],
    seed: Option[String],
    prompt: String
): String =
  val context = List(preamble, seed).flatten.filter(_.nonEmpty).mkString("\n\n")
  if context.isEmpty then prompt else s"$context\n\n---\n\n$prompt"
