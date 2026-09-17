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
import orca.progress.{ProgressLog, SessionKey, SessionRecord}

import scala.annotation.implicitNotFound
import scala.util.NotGiven

/** Compile-time evidence that no [[InStage]] capability is in scope — i.e. the
  * call site is lexically outside a `stage(...)` body. Lexical only: a helper
  * taking just `(using FlowControl)` that is invoked inside a stage still
  * compiles, so each door that takes this evidence keeps a runtime in-stage
  * check as the backstop.
  */
@implicitNotFound(
  "agent.session(...), openPrFromBranch(...) and openPrIfGitHub(...) must be " +
    "called outside a stage, at the flow-body top level: mint sessions before " +
    "stages and run them inside stages via the FlowSession handle " +
    "(session.run / session.resultAs[...].run); the PR helpers run their own " +
    "stages."
)
final class OutsideStage private ()
object OutsideStage:
  given (using NotGiven[InStage]): OutsideStage = new OutsideStage

/** A durable, resumable LLM-session handle — the single door for sessions that
  * must survive a flow crash and resume. Obtain one with `agent.session(name,
  * detail, seed)`. It owns the probe → seed/preamble → run → persist protocol,
  * so a durable run can never silently skip seeding or wire-id persistence the
  * way a bare `agent.run` / `chat.run` turn does.
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
  * The handle is a plain immutable value with no stage affinity of its own —
  * only the capabilities its methods require ([[InStage]], [[WorkspaceWrite]])
  * are stage-scoped. Mint it via `agent.session` (outside-stage only — see
  * there) and close over it into any later `stage(...)`.
  */
final class FlowSession[B <: BackendTag] private[orca] (
    private[orca] val agent: Agent[B],
    /** The underlying reserved session id — the escape hatch (see the class
      * scaladoc). Prefer [[run]] / [[resultAs]]; reach for `.id` only to mint
      * an ephemeral continuation via `agent.chat(id)`.
      */
    val id: SessionId[B],
    /** The name half of the session's key. Carried onto every turn's
      * `OrcaEvent.SessionCommitted`, which is what names the session in the run
      * manifest; the detail half stays in the progress log.
      */
    private[orca] val name: String
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
      .runWithSession(
        effectivePrompt(agent, id, prompt),
        id,
        sessionName = Some(name),
        config = None,
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
    new FlowSessionCall(agent, id, name)

/** Structured-durable gateway for a [[FlowSession]] (obtained via
  * [[FlowSession.resultAs]]). Fixes the output type `O`, and exposes a single
  * `run` (no `autonomous`/`interactive` split): interactive durable sessions
  * are deliberately not offered — see the [[FlowSession]] class scaladoc.
  */
final class FlowSessionCall[B <: BackendTag, O] private[orca] (
    agent: Agent[B],
    id: SessionId[B],
    name: String
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
        sessionName = Some(name),
        config = None,
        emitPrompt = emitPrompt
      )
    persistResumeWireId(agent, id)
    output

/** Get-or-create session extension for `Agent`. Lives in the `flow` module so
  * it can depend on [[FlowControl]] while [[Agent]] stays in `tools`.
  */
extension [B <: BackendTag](agent: Agent[B])
  /** Get-or-create a durable [[FlowSession]] keyed by `(name, detail)` in this
    * run's log.
    *
    * `name` is the session's role — `implementer`, `final-fixer` — restricted
    * to letters, digits, `-` and `_`, and the only half of the key that leaves
    * the log: it names the session in the run manifest and is what `orca
    * continue <name>` matches. `detail` says which session under that name this
    * is, typically the task it serves; it is free text, and empty — the default
    * — is the key for the one session its name ever has.
    *
    * Reserves a [[SessionId]] and records key + id + seed in the progress log,
    * then returns a [[FlowSession]] wrapping it; the backend conversation is
    * created lazily on the handle's first gated `run`. On resume, wraps the id
    * recorded at this key rather than minting a second. The seed is only
    * recorded here; [[FlowSession.run]] applies it on first use and replays it
    * on loss.
    *
    * Identity is what the key says, not where the call sits: inserting,
    * reordering or skipping other `session(...)` calls between runs leaves this
    * one alone, while a changed `detail` — a re-plan rewording the task it
    * names, say — is a different session, minted fresh and primed from the
    * seed.
    *
    * Minting one key twice in a run throws (see
    * [[StageFrames.claimSessionKey]]): two handles driving one conversation is
    * an authoring mistake, and the detail is where an author asks for a second
    * session over the same work.
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
  def session(name: String, detail: String = "", seed: String)(using
      fc: FlowControl,
      outside: OutsideStage
  ): FlowSession[B] =
    validateSessionName(name)
    // A session minted inside a stage that gets skipped on resume would never
    // re-mint, leaving later stages driving a handle nothing recorded — so
    // require the flow-body top level (ADR 0018 §2.6). [[OutsideStage]] rejects
    // the direct in-stage call at compile time; this catches the indirect path
    // it can't see (a FlowControl-only helper invoked from within a stage).
    if fc.inStage then
      throw new OrcaFlowException(
        "agent.session(...) must be called outside a stage: mint sessions at " +
          "the flow-body top level, before stages, and run them inside stages " +
          "via the FlowSession handle (session.run / session.resultAs[...].run)."
      )
    val key = SessionKey(name, detail)
    fc.claimSessionKey(key)
    new FlowSession(agent, resolveSessionId(agent, key, seed), name)

private val SessionNamePattern = "[A-Za-z0-9_-]+".r

/** Names are shown as-is in the run manifest and matched by `orca continue
  * <name>`, so they stay bare identifiers; anything a reader would have to
  * quote belongs in the session's detail instead.
  */
private def validateSessionName(name: String): Unit =
  if !SessionNamePattern.matches(name) then
    throw new OrcaFlowException(
      s"session name '$name' is not usable — use letters, digits, '-' and " +
        "'_' (e.g. \"implementer\"), and pass free text as the session's " +
        "detail instead."
    )

/** The reuse-or-mint decision behind `agent.session(name, detail, seed)`: look
  * up any session already recorded at `key` and either reuse it (backend tag
  * matches, recorded id parses) or mint a fresh one — on a backend swap, a
  * corrupt/mismatched recorded id, or no record at all. See `session`'s
  * scaladoc for the reuse contract each branch upholds.
  */
private def resolveSessionId[B <: BackendTag](
    agent: Agent[B],
    key: SessionKey,
    seed: String
)(using fc: FlowControl): SessionId[B] =
  fc.progressStore.load().flatMap(_.sessions.find(_.key == key)) match
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
  val currentTag = agent.backendTag.map(_.wireName)
  recorded.backend match
    case Some(recordedTag) if currentTag != Some(recordedTag) =>
      // Backend swapped between runs: `recorded.id` is meaningful only in the
      // old backend's registry, so mint fresh rather than reuse it.
      warnBackendSwap(fc, key, recordedTag, currentTag)
      mintSession(agent, key, seed)
    case _ =>
      // Tags match (or the record predates tagging). The recorded id is
      // log-sourced and untrusted: parse it rather than resume against a
      // value that could carry a path/regex/URL injection downstream; a
      // parse failure mints fresh like the tag-mismatch case.
      SessionId.parse[B](recorded.id) match
        case Some(validId) =>
          // Reuse is the safe fallback (ADR 0018 §2.6): a recorded seed
          // differing from this call's means the seed was edited between
          // runs, but the session is reused either way — surface the
          // divergence rather than resume silently.
          warnIfSeedDiffers(fc, key, recorded.seed, seed)
          validId
        case None =>
          warnInvalidRecordedId(fc, key)
          mintSession(agent, key, seed)

private def warnBackendSwap(
    fc: FlowControl,
    key: SessionKey,
    recordedTag: String,
    currentTag: Option[String]
): Unit =
  fc.emit(
    OrcaEvent.Step(
      s"warning: session '${key.label}' was minted on " +
        s"$recordedTag; this agent is " +
        s"${currentTag.getOrElse("untagged")} — minting fresh"
    )
  )

private def warnIfSeedDiffers(
    fc: FlowControl,
    key: SessionKey,
    recordedSeed: String,
    seed: String
): Unit =
  if recordedSeed != seed then
    fc.emit(
      OrcaEvent.Step(
        s"warning: session '${key.label}' recorded seed differs " +
          "for this key — the seed was edited; reusing the recorded session"
      )
    )

private def warnInvalidRecordedId(fc: FlowControl, key: SessionKey): Unit =
  fc.emit(
    OrcaEvent.Step(
      s"warning: session '${key.label}' has an invalid recorded id " +
        "— minting fresh"
    )
  )

/** Mint a fresh session id, record `(key, id, seed, backend)` in the progress
  * log (replacing any existing record at the same key — see
  * `ProgressStore.upsertSession`), and return it. Shared by every arm of
  * `agent.session(name, detail, seed)`'s reuse match that must not trust a
  * stale/mismatched/corrupt recorded id. Mints its own [[WorkspaceWrite]] via
  * [[RuntimeInStage]] — see `session`'s scaladoc.
  */
private def mintSession[B <: BackendTag](
    agent: Agent[B],
    key: SessionKey,
    seed: String
)(using fc: FlowControl): SessionId[B] =
  val freshId = SessionId.fresh[B]
  given WorkspaceWrite = RuntimeInStage.workspaceToken()
  fc.progressStore.upsertSession(
    SessionRecord(
      name = key.name,
      detail = key.detail,
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
          s"warning: session '${record.fold("?")(_.key.label)}' — backend " +
            "conversation not found; re-seeding (prior conversation history " +
            "is lost)"
        )
      )
    val seed = record.map(_.seed).filter(_.nonEmpty)
    val preamble = progressPreamble(log, fc.git.headCommit())
    composePrimedPrompt(preamble, seed, text)

/** After a run, persist the backend's now-learned resume wire id (durable
  * backends only — one without a probe returns `None`), so a resumed run can
  * rehydrate the map and probe the right session. Also self-heals
  * [[SessionRecord.backend]] from `None` (an untagged record) to `agent`'s
  * current tag, on the very run that just proved this `agent` owns it, rather
  * than waiting for a second `session(...)` call. Upserts only when the learned
  * wire id or the healed tag differs from what is recorded, so a no-op run
  * writes nothing. Takes the [[WorkspaceWrite]] token explicitly to keep these
  * writes flow-thread-only (ADR 0018 §6).
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

/** Compose the progress preamble from completed stage names in the log and the
  * commit the working tree sits at. Returns `None` if there are no completed
  * entries (first run).
  *
  * `headCommit` is passed in rather than read here, so the rendered text is a
  * function of the arguments alone.
  *
  * This reaches a model only where [[effectivePrompt]] injects it — when the
  * backend conversation is fresh or lost. That is the common resumed-run case
  * but not every one: an agent whose conversation is still live gets the
  * caller's text verbatim and never sees this.
  *
  * The wording stays neutral about interruption because the same preamble
  * primes a session first used mid-run, after an earlier stage completed — and
  * it speaks of a stage that did not complete rather than of uncommitted work
  * in general, since a stage's own edits ARE committed at its boundary.
  */
private def progressPreamble(
    log: Option[ProgressLog],
    headCommit: Option[String]
): Option[String] =
  val completed = log.map(_.entries.map(_.name)).getOrElse(Nil)
  if completed.isEmpty then None
  else
    val tree =
      headCommit.fold("")(c => s" The working tree is at commit $c.")
    Some(
      s"Progress so far: completed ${completed.mkString(", ")}.$tree " +
        "Their work is committed; a stage that did not complete left nothing " +
        "behind. Read the files rather than assuming what earlier stages " +
        "left. Continue from here."
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
