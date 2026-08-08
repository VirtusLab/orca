package orca.backend

import java.util.concurrent.atomic.AtomicBoolean

import orca.events.OrcaListener
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  EnforcementCell,
  EnforcementNotice,
  SessionId,
  StructuredOutputMode,
  ToolSet,
  TurnDispatch
}

import ox.Ox

/** SPI implemented per backend (Claude, Codex, …), called from the
  * autonomous-text and structured-output paths ([[AutonomousTextCall]],
  * [[AgentCall]]).
  *
  * Each method takes a `session: SessionId[B]` — the same value across calls;
  * the backend decides internally whether this is a first invocation (session
  * needs creating) or a continuation. `runAutonomous` runs to completion
  * off-screen and returns the result; `runInteractive` returns a live
  * [[Conversation]] the caller drives through an [[Interaction]]. Both are
  * final: they run the turn-entry gate (close check + enforcement notice), then
  * delegate to the `doRun*` hooks a backend implements.
  *
  * `prompt` is the full wire-level message sent to the agent, with all template
  * scaffolding, schema, and rules already wrapped around the user's input.
  * `displayPrompt` (interactive only) is what the renderer shows the user.
  */
trait AgentBackend[B <: BackendTag](
    /** Backing store for [[isClosed]]/[[markClosed]]. Defaults to a fresh,
      * unshared flag, correct for every backend whose builders go through
      * `BaseAgent.copyTool` and stay on the SAME backend instance. A builder
      * that instead constructs a SIBLING backend (today only claude's
      * [[orca.tools.claude.ClaudeBackend.withNetworkTools]]) MUST pass the
      * parent's `closedFlag` here, so `markClosed()` on either instance is
      * visible through both — otherwise a handle derived via that builder and
      * leaked past flow-end bypasses the use-after-close guard entirely.
      */
    private[orca] val closedFlag: AtomicBoolean = new AtomicBoolean(false),
    /** Which enforcement notices this backend has already given. A SIBLING
      * backend must be passed the parent's, for the same reason as
      * [[closedFlag]] above: a fresh log would say everything a second time.
      */
    private[orca] val enforcementNotice: EnforcementNotice =
      new EnforcementNotice
):
  /** Run one autonomous turn against `session` and return its result.
    *
    * `events` receives per-tool-use and per-message progress as the subprocess
    * runs. Defaults to a no-op listener for callers (typically tests) that
    * don't observe progress.
    *
    * `outputSchema`, when supplied, is the JSON Schema the final assistant
    * payload must conform to. Backends that enforce schemas natively (claude's
    * `--json-schema`) pass it to the CLI; others can ignore it. Either way the
    * schema is forwarded to the conversation so the drain can recognise "the
    * agent's last message IS the structured payload" and suppress the raw JSON
    * from the user log — the caller surfaces it via
    * `OrcaEvent.StructuredResult` instead.
    */
  final def runAutonomous(
      prompt: String,
      session: SessionId[B],
      config: AgentConfig,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): AgentResult[B] =
    checkNotClosed()
    // Per call, not once per session: the first call commits the session, so a
    // caller's corrective re-prompt dispatches as `Resumed` — a different
    // guarantee on codex, and hence possibly a different notice.
    announceEnforcementShortfall(config, session, events)
    doRunAutonomous(prompt, session, config, events, outputSchema)

  /** This backend's autonomous turn, run once [[runAutonomous]]'s gate has
    * passed.
    */
  protected def doRunAutonomous(
      prompt: String,
      session: SessionId[B],
      config: AgentConfig,
      events: OrcaListener,
      outputSchema: Option[String]
  ): AgentResult[B]

  /** Launch an interactive session against `session` and return a live
    * [[Conversation]] the caller hands to [[Interaction.drive]]. The backend
    * owns the subprocess and event parsing; the channel owns UX.
    *
    * `outputSchema` is the JSON Schema the agent's final reply must conform to,
    * or `None` for free-form text. Backends that support structured-output
    * validation (claude's `--json-schema`) enforce it; others ignore it and let
    * the caller validate post-hoc.
    *
    * `events` carries the turn-entry notice only — everything the conversation
    * itself produces reaches the caller through the returned [[Conversation]],
    * which is why the backend hook never sees this listener.
    */
  final def runInteractive(
      prompt: String,
      session: SessionId[B],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String],
      events: OrcaListener = OrcaListener.noop
  )(using Ox): Conversation[B] =
    checkNotClosed()
    announceEnforcementShortfall(config, session, events)
    doRunInteractive(prompt, session, displayPrompt, config, outputSchema)

  /** This backend's interactive turn, run once [[runInteractive]]'s gate has
    * passed.
    */
  protected def doRunInteractive(
      prompt: String,
      session: SessionId[B],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[B]

  /** The working directory the agent subprocess sees, fixed for this backend's
    * whole lifetime — every spawn and every session-existence probe runs
    * against this same path.
    */
  def workDir: os.Path

  /** This backend's whole session capability as one structural value: the id
    * scheme plus, for durable backends, the existence probe (see
    * [[SessionSupport.durable]] / [[SessionSupport.ephemeral]]). The framework
    * reaches sessions exclusively through this, so a backend cannot half-wire
    * resume by providing persist/probe/register piecemeal.
    */
  def sessions: SessionSupport[B]

  /** Runtime value of the compile-time tag `B`; lets the runtime record which
    * backend a session belongs to.
    */
  def tag: B

  /** How strongly THIS backend enforces the restriction a `(tools,
    * autoApprove)` combination requests on a `dispatch` turn, and why — a pure
    * classification of the tier and approval flags this backend's `*Args` would
    * build, surfaced as data because the answer differs materially across
    * backends. It does not see what a turn additionally GRANTS on top of the
    * tier (claude's MCP tool names, pi's ask-user extension), which widens the
    * tools on offer without changing how the tier itself is enforced.
    *
    * Abstract, not defaulted to `Enforcement.Ignored`, so a new backend cannot
    * ship without answering this; the `*Args` implementations match `dispatch`
    * exhaustively, so it cannot answer for fresh turns only. Real backends
    * delegate to their `*Args.enforcementCell`; test doubles that aren't
    * exercising it mix in the testkit's `StubEnforcementCell`.
    *
    * @see
    *   [[orca.agents.Enforcement]] for what the levels mean.
    */
  def enforcementCell(
      tools: ToolSet,
      autoApprove: AutoApprove,
      dispatch: TurnDispatch
  ): EnforcementCell

  /** Report, at most once per distinct sentence for this backend, that the turn
    * about to run against `session` asked for a restriction this backend cannot
    * apply mechanically. Delegates to [[enforcementNotice]], which owns both
    * the wording and the "already said" bookkeeping.
    */
  private def announceEnforcementShortfall(
      config: AgentConfig,
      session: SessionId[B],
      events: OrcaListener
  ): Unit = enforcementNotice.announceShortfall(this, config, session, events)

  /** How THIS backend's wire delivers a structured (`resultAs[O]`) payload
    * ([[orca.agents.StructuredOutputMode]]). Prompt assembly
    * ([[orca.agents.Prompts.autonomous]]) branches on it, so a backend that
    * misdeclares gets an instruction that contradicts its wire and steers weak
    * models into malformed replies.
    *
    * Abstract, not defaulted, for the same reason as [[enforcementCell]]. Real
    * backends declare what their CLI actually does; test doubles that never
    * assemble prompts add a one-line `RawText` override.
    */
  def structuredOutputMode: StructuredOutputMode

  /** Release background resources this backend owns (processes, servers, drain
    * forks). Called by the runtime in the flow body's `finally`, BEFORE the
    * flow scope joins its forks — a resource whose teardown unblocks a
    * non-interruptible read must happen here, not in a `releaseAfterScope`
    * finalizer (Ox runs those after the join). Idempotent; default no-op.
    */
  def close(): Unit = ()

  // The use-after-close latch lives on the backend, not the Agent instance:
  // every builder goes through `BaseAgent.copyTool`, which constructs a new
  // agent sharing this same backend — a per-agent flag would reset to "open"
  // on every derived handle, letting a leaked handle bypass the guard.

  /** Latch this backend as closed — its owning flow has ended, and every run
    * entry point gated on [[isClosed]] must refuse from now on. Called by
    * `BaseAgent.close()` before [[close]]; separate from it so a subclass
    * overriding `close()` for resource teardown cannot forget the latch.
    */
  private[orca] final def markClosed(): Unit = closedFlag.set(true)

  /** Whether [[markClosed]] has run — i.e. the flow that created this backend
    * (and every agent handle sharing it) has ended.
    */
  private[orca] final def isClosed: Boolean = closedFlag.get()

  /** Refuse a run against a backend whose flow has ended, so a leaked agent
    * handle can't emit to a closed run's dispatcher. [[runAutonomous]] /
    * [[runInteractive]] gate every turn; the agent surface (`BaseAgent`) and
    * the structured gateway (`DefaultAgentCall`, which holds no agent of its
    * own) gate earlier still, so a dead handle fails at the door rather than
    * one frame into the backend.
    */
  private[orca] final def checkNotClosed(): Unit =
    if isClosed then
      throw new orca.OrcaFlowException(AgentBackend.ClosedMessage)

object AgentBackend:
  /** The use-after-close guard's user-facing message, thrown by every
    * `isClosed` gate so a leaked-handle failure reads identically no matter
    * which gate caught it.
    */
  private[orca] val ClosedMessage: String =
    "agent used after its flow ended — agents are scoped to the flow(...) that created them"
