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
  case ToolUse(tool: String, args: String)

  /** A single instantaneous note in the event log — neither a stage nor a
    * stream-of-text. Tools emit these for discrete progress: "switched to
    * branch X", "discarded N issues", etc.
    */
  case Step(message: String)

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
    */
  case TokensUsed(
      agent: String,
      model: Option[Model],
      usage: Usage,
      role: Option[String] = None
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
    * it as a one-line `●`; full text reaches non-terminal listeners.
    */
  case AssistantMessage(text: String)

  case Error(message: String)

  /** Fires when a session's first turn commits — its wire id, if any, is known
    * by then (ADR 0021 §8). Once per (backend, clientId, wireId) commit;
    * listeners dedup on a resumed session's later turns. `wireId` is the
    * persistable id ([[orca.agents.Agent.resumeWireId]]) — `None` for backends
    * whose sessions don't survive the run (pi), so a non-resumable commit still
    * fires accurately.
    */
  case SessionCommitted(
      backend: String,
      clientId: String,
      wireId: Option[String],
      agent: String,
      role: Option[String]
  )

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
