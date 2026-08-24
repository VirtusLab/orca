package orca.events

import orca.agents.Model

/** Flow-level event fanned out to every registered [[OrcaListener]]. Covers
  * stage transitions, tool invocations, token usage, structured results, and
  * errors.
  *
  * Events exist for observability only — no runtime decision reads them back,
  * listeners return `Unit`, and the dispatcher isolates the emitter from
  * listener failures (see [[OrcaListener]]), so an observer cannot alter the
  * flow's outcome. Anything that drives logic travels through return values or
  * exceptions instead.
  *
  * Distinct from [[orca.backend.ConversationEvent]], which is scoped to a
  * single live LLM conversation and consumed only by the
  * [[orca.backend.Interaction]] that drives it; `OrcaEvent`s fan out to all
  * listeners.
  */
enum OrcaEvent:
  case StageStarted(name: String)
  case StageCompleted(name: String)

  /** One tool invocation by the agent named in `agent`. Backends emit `None` —
    * a drain doesn't know which agent it runs for;
    * [[OrcaListener.attributedTo]] wraps it and stamps the name on the way out.
    * Renderers use the name to tell apart the interleaved lines of agents
    * running in parallel.
    */
  case ToolUse(tool: String, args: String, agent: Option[String] = None)

  /** A single instantaneous note in the event log — neither a stage nor a
    * stream-of-text. Tools emit these for discrete progress: "switched to
    * branch X", "discarded N issues", etc.
    */
  case Step(message: String)

  /** Something true of the whole run rather than of the stage it surfaced in —
    * today, a restriction a backend cannot apply mechanically
    * ([[orca.agents.EnforcementNotice]]). Distinct from [[Step]] because a
    * caveat about what orca can guarantee must not read as progress: the
    * terminal listener prints it un-indented with a `!`, so the stage it
    * happened to fire under doesn't look like its scope.
    */
  case Caveat(message: String)

  /** Token usage for a single LLM call, attributed along three independent axes
    * that `CostTracker` summarises separately:
    *
    *   - `agent` is the [[Agent.name]] that issued the call — always the bare
    *     identity (`claude`, `codex`, …), never a display-prefixed copy.
    *   - `model` is the concrete model the backend reports it served the call
    *     with. `None` when the response didn't carry it and no model was pinned
    *     via `AgentConfig.model`. Coarser groupings (family / provider) are not
    *     a fourth axis: they are derivable at display time, whereas emission
    *     sites would have to guess them for provider-agnostic backends (orca
    *     doesn't normalise model ids — see [[orca.agents.Model]]).
    *   - `role` is the [[Agent.role]] tag, set at the emission edge (e.g. the
    *     review loop's `Some("reviewer")`, via `withRole`). `None` for an
    *     ordinary call. Purely a grouping/display hint.
    *
    * `attempt` is this turn's 1-based position among the turns of a single
    * call: 2 or more means a retry re-sent the prompt and paid for it again. It
    * counts turns, not tries — an attempt that fails before the model runs
    * emits no event, so it doesn't shift the index of the turn that follows.
    * Emission sites that never retry leave the default.
    *
    * `cost` is this turn's resolved spend, filled in once at the dispatch
    * boundary so every listener reads the same figure. Emitters pass `None`:
    * pricing lives in the flow module, and a listener that priced the event
    * itself could disagree with the printed summary and the on-disk cost log.
    *
    * `session` is [[OrcaEvent.sessionKey]] for the conversation this turn ran
    * in — the same key [[SessionCommitted]] is deduplicated under, so turns and
    * sessions join on it. Two turns of one session carry the same value; the
    * first turn of a session is the earliest turn carrying it. `None` only
    * where the emitter has no conversation to name (test stubs).
    */
  case TokensUsed(
      agent: String,
      model: Option[Model],
      usage: Usage,
      role: Option[String] = None,
      attempt: Int = 1,
      session: Option[String] = None,
      cost: Option[Cost]
  )

  /** The agent's final structured payload, after parsing succeeded. `raw` is
    * the verbatim text the agent produced (typically JSON); `summary` is the
    * `Announce[O]`-derived human-readable form, tri-state:
    *
    *   - `Some(text)` — a summary to show;
    *   - `Some("")` — the `Announce[O]` deliberately says nothing (the call
    *     site narrates the outcome itself); renderers show nothing;
    *   - `None` — no specific `Announce[O]` exists; renderers fall back to the
    *     raw payload so the result stays visible.
    */
  case StructuredResult(raw: String, summary: Option[String])

  /** The human-readable input sent to the agent at the start of an autonomous
    * call. Fires once per call, before [[TokensUsed]] / [[StructuredResult]] /
    * [[AssistantMessage]]. Interactive calls surface this through the
    * conversation renderer's own user-message line and do not emit this event.
    * The terminal listener renders it as a one-line `▸`; full text reaches
    * non-terminal listeners.
    */
  case UserPrompt(text: String)

  /** A turn of free-form prose from the agent, one per
    * [[ConversationEvent.AssistantTurnEnd]] on the autonomous drain (the
    * interactive renderer surfaces these itself). The terminal listener renders
    * it as a one-line `●`; full text reaches non-terminal listeners. `agent`
    * carries the same attribution as [[ToolUse]].
    */
  case AssistantMessage(text: String, agent: Option[String] = None)

  case Error(message: String)

  /** Fires once [[orca.backend.SessionSupport]] commits a session's client→wire
    * id mapping ([[orca.backend.SessionSupport.commit]] / `commitAfterDrain`) —
    * unrelated to a git commit; "commits" here means the mapping becomes
    * durable enough for a later call to resume against it (ADR 0021 §8). Fires
    * once per (harness, clientId, wireId) commit; listeners dedup on a resumed
    * session's later turns. `harness` is the backend's wire name — the one
    * string the persisted manifest also calls `harness`
    * ([[orca.runner.manifest.ManifestSession]]). `wireId` is the persistable id
    * ([[orca.agents.Agent.resumeWireId]]) — `None` for backends that keep
    * nothing durably resumable, so a non-resumable commit still fires
    * accurately. `sessionName` is the name the flow minted the session under
    * (`agent.session(name, seed)`) — `None` for a one-shot or chat turn, which
    * has no name.
    */
  case SessionCommitted(
      harness: String,
      clientId: String,
      wireId: Option[String],
      sessionName: Option[String],
      agent: String,
      role: Option[String]
  )

object OrcaEvent:
  /** The one identity a session is known by across events: its wire id once the
    * backend has minted one, else the client id orca allocated. Named here so
    * [[OrcaEvent.TokensUsed.session]] and the manifest writer's session dedup
    * key cannot drift apart — if they did, turns would stop joining to the
    * sessions that produced them.
    */
  def sessionKey(clientId: String, wireId: Option[String]): String =
    wireId.getOrElse(clientId)

/** Sink for [[OrcaEvent]]s.
  *
  * **Implementations MUST be thread-safe.** `onEvent` is called from parallel
  * agent forks (concurrent reviewers, `ox.par` LLM calls) without external
  * synchronization, so listeners mutating shared state must do so atomically,
  * and listeners delegating to other sinks must ensure those tolerate
  * concurrent calls too.
  *
  * A throw from `onEvent` never reaches the emitting flow and does not end the
  * run: the dispatcher logs it at ERROR, announces it on stderr, and
  * quarantines that one listener (presumed unrecoverable) while every other
  * listener keeps seeing every event. `emit` being total is load-bearing:
  * failure-teardown paths emit from `catch` blocks where a listener throw would
  * otherwise mask the original failure (see `FlowLifecycle`).
  */
trait OrcaListener:
  def onEvent(event: OrcaEvent): Unit

object OrcaListener:
  /** Drops every event. Default for tools that run without a wired-up
    * dispatcher (unit tests, lightweight scripts).
    */
  val noop: OrcaListener = (_: OrcaEvent) => ()

  /** Stamps `agentName` onto the two display events a turn produces
    * ([[OrcaEvent.ToolUse]], [[OrcaEvent.AssistantMessage]]) on their way to
    * `downstream`; every other event passes through untouched. Wrapped around
    * the listener handed to a backend drain, which emits those events without
    * knowing which agent it is running for.
    */
  def attributedTo(downstream: OrcaListener, agentName: String): OrcaListener =
    case e: OrcaEvent.ToolUse =>
      downstream.onEvent(e.copy(agent = Some(agentName)))
    case e: OrcaEvent.AssistantMessage =>
      downstream.onEvent(e.copy(agent = Some(agentName)))
    case other => downstream.onEvent(other)
