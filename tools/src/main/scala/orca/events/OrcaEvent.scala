package orca.events

import orca.StagePath
import orca.agents.{BackendTag, Model, SessionKey}

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
  * Distinct from [[orca.backend.TurnEvent]], which is scoped to one turn and
  * consumed only by the [[orca.backend.Interaction]] that drives it;
  * `OrcaEvent`s fan out to all listeners.
  */
enum OrcaEvent:
  /** A stage began, whether it runs or replays. Every one is followed by the
    * [[StageEnded]] of the same `path`, after those of any stages nested in it.
    * Only `stage` emits the pair, on the flow's owner thread, which is what
    * lets a listener keep the open stages as a plain stack.
    */
  case StageStarted private[orca] (path: StagePath.Stage)

  /** The stage at `path` ended; see [[StageStarted]]. */
  case StageEnded private[orca] (path: StagePath.Stage, outcome: StageOutcome)

  /** One tool invocation by the agent named in `agent`. Backends emit `None` —
    * a drain doesn't know which agent it runs for;
    * [[OrcaListener.attributedTo]] wraps it and stamps the name on the way out.
    * Renderers use the name to tell apart the interleaved lines of agents
    * running in parallel.
    */
  case ToolUse(tool: String, args: String, agent: Option[String] = None)

  /** A tool call refused for lack of permission, including an autonomous turn's
    * approval request, which has no user to ask. Not a tool that ran and
    * failed, and not a user's "no" at an approval prompt. `agent` carries the
    * same attribution as [[ToolUse]].
    */
  case ToolDenied(tool: String, agent: Option[String])

  /** A single instantaneous note in the event log — neither a stage nor a
    * stream-of-text. Tools emit these for discrete progress: "switched to
    * branch X", "Opened PR: <url>", etc.
    */
  case Step(message: String)

  /** Orca's own housekeeping on the user's branch — today the progress-log and
    * settings commits ([[orca.tools.RuntimeGit.commitOnly]] /
    * `forceCommitOnly`). Distinct from [[Step]] because none of it is work on
    * the task: the terminal log leaves it out, where it would read as progress,
    * while the trace file keeps it. A message is never matched to decide this —
    * the emitter picks the case.
    */
  case Bookkeeping(message: String)

  /** Something true of the whole run rather than of the stage it surfaced in —
    * today, a restriction a backend cannot apply mechanically
    * ([[orca.agents.EnforcementNotice]]). Distinct from [[Step]] because a
    * caveat about what orca can guarantee must not read as progress: the
    * terminal listener prints it un-indented with a `!`, so the stage it
    * happened to fire under doesn't look like its scope.
    */
  case Caveat(message: String)

  /** Token usage for one turn, as its emitter reports it — unpriced. The
    * dispatcher turns each one into a [[TokensUsed]] carrying the turn's cost,
    * so listeners behind it never see this event. Attributed along three
    * independent axes that `CostTracker` summarises separately:
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
    * `turn` is this turn's 1-based position among the turns of a single call: 2
    * or more means a retry re-sent the prompt and paid for it again. It counts
    * turns, not tries — a try that fails before the model runs emits no event,
    * so it doesn't shift the index of the turn that follows.
    *
    * `conversationKey` is [[OrcaEvent.conversationKey]] for the conversation
    * this turn ran in — the same key [[SessionCommitted]] is deduplicated
    * under, so turns and sessions join on it. Two turns of one session carry
    * the same value; the first turn of a session is the earliest turn carrying
    * it.
    */
  case UnpricedTurn(
      agent: String,
      model: Option[Model],
      usage: Usage,
      role: Option[String],
      turn: Int,
      conversationKey: String
  )

  /** `spend` with its cost resolved. Emitters send [[UnpricedTurn]]; the
    * dispatcher builds this, so every listener behind it reads the same figure.
    * `cost` is `None` when the turn could not be priced.
    */
  case TokensUsed private[orca] (spend: UnpricedTurn, cost: Option[Cost])

  /** The agent's final structured payload, after parsing succeeded. `raw` is
    * the verbatim text the agent produced (typically JSON); `announcement` is
    * what the result type's `Announce[O]` asks renderers to show. `agent`
    * carries the same attribution as [[ToolUse]].
    */
  case StructuredResult(
      raw: String,
      announcement: Announcement,
      agent: Option[String]
  )

  /** The human-readable input sent to the agent at the start of a call. Fires
    * once per call, before [[UnpricedTurn]] / [[StructuredResult]] /
    * [[AssistantMessage]]. The terminal listener renders it as a one-line `▸`;
    * full text reaches non-terminal listeners.
    */
  case UserPrompt(text: String)

  /** One message of free-form prose from the agent, one per
    * [[orca.backend.TurnEvent.AssistantMessageEnd]]. The terminal listener
    * renders it as a one-line `●`; full text reaches non-terminal listeners.
    * `agent` carries the same attribution as [[ToolUse]].
    */
  case AssistantMessage(text: String, agent: Option[String] = None)

  /** A failure worth showing. `agent` names the agent whose turn produced it,
    * carrying the same attribution as [[ToolUse]]; `None` for a failure of the
    * flow itself (a stage that threw, `fail(...)`), which is what tells the two
    * apart when an agent failure is followed by the stage error it caused.
    */
  case Error(message: String, agent: Option[String] = None)

  /** Fires once [[orca.backend.SessionSupport]] commits a session's client→wire
    * id mapping ([[orca.backend.SessionSupport.commit]] / `commitAfterDrain`) —
    * unrelated to a git commit; "commits" here means the mapping becomes
    * durable enough for a later call to resume against it (ADR 0021 §8). Fires
    * once per (backend, clientId, wireId) commit; listeners dedup on a resumed
    * session's later turns. `backend` is persisted as the manifest's `backend`
    * ([[orca.runner.manifest.ManifestSession]]). `wireId` is the persistable id
    * ([[orca.agents.Agent.resumeWireId]]) — `None` for backends that keep
    * nothing durably resumable, so a non-resumable commit still fires
    * accurately. `sessionKey` is the key the flow minted the session under
    * (`agent.session(name, seed)`) — `None` for a one-shot or chat turn, which
    * is minted under no key.
    */
  case SessionCommitted(
      backend: BackendTag,
      clientId: String,
      wireId: Option[String],
      sessionKey: Option[SessionKey],
      agent: String,
      role: Option[String]
  )

  /** Fires once per attempt, right after the run is bound to its branch —
    * fresh, resumed and `--skip-branch` runs alike. `branch` is the branch
    * actually bound, which may be a fallback name rather than the one the
    * naming strategy proposed. The attempt manifest writer records it.
    */
  case BranchBound(branch: String)

object OrcaEvent:
  /** The one identity a backend conversation is known by across events: its
    * wire id once the backend has minted one, else the client id orca
    * allocated. Named here so [[OrcaEvent.UnpricedTurn.conversationKey]] and
    * the manifest writer's session dedup key cannot drift apart — if they did,
    * turns would stop joining to the sessions that produced them. Distinct from
    * [[orca.agents.SessionKey]], which is the `(name, stage)` a flow minted a
    * durable session under.
    */
  def conversationKey(clientId: String, wireId: Option[String]): String =
    wireId.getOrElse(clientId)

/** Sink for [[OrcaEvent]]s.
  *
  * **Implementations MUST be thread-safe.** `onEvent` is called from parallel
  * agent forks (concurrent reviewers, `ox.par` LLM calls) without external
  * synchronization, so listeners mutating shared state must do so atomically,
  * and listeners delegating to other sinks must ensure those tolerate
  * concurrent calls too.
  *
  * A listener's work completes, or fails, on the calling thread: one backed by
  * an Ox actor uses `ask`, never `tell`, whose failure would end the actor's
  * scope instead of reaching the dispatcher. The actor's scope must outlive
  * every emitter.
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

  /** Stamps `agentName` onto the four display events a turn produces
    * ([[OrcaEvent.ToolUse]], [[OrcaEvent.ToolDenied]],
    * [[OrcaEvent.AssistantMessage]], [[OrcaEvent.Error]]) on their way to
    * `downstream`; every other event passes through untouched. Wrapped around
    * the listener handed to a backend drain, which emits those events without
    * knowing which agent it is running for.
    */
  def attributedTo(downstream: OrcaListener, agentName: String): OrcaListener =
    case e: OrcaEvent.ToolUse =>
      downstream.onEvent(e.copy(agent = Some(agentName)))
    case e: OrcaEvent.ToolDenied =>
      downstream.onEvent(e.copy(agent = Some(agentName)))
    case e: OrcaEvent.AssistantMessage =>
      downstream.onEvent(e.copy(agent = Some(agentName)))
    case e: OrcaEvent.Error =>
      downstream.onEvent(e.copy(agent = Some(agentName)))
    case other => downstream.onEvent(other)
