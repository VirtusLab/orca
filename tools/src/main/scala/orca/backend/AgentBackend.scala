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
import ox.{Ox, supervised}

/** SPI implemented per backend (Claude, Codex, …), called from the
  * autonomous-text and structured-output paths ([[AutonomousTextCall]],
  * [[AgentCall]]).
  *
  * A backend implements [[open]]: start one turn and return it as a live
  * [[Conversation]]. The final [[runAutonomous]] / [[runInteractive]] own the
  * rest of the turn for every backend: the turn-entry gate (close check,
  * fresh-vs-resume dispatch, enforcement notice), the per-turn `supervised`
  * scope, draining or driving the conversation, committing the session, and
  * teardown.
  *
  * Each run method takes a `session: SessionId[B]` — the same value across
  * calls; [[SessionSupport.dispatchFor]] decides whether this is a first
  * invocation or a continuation. Callers must not share a session id across
  * concurrent calls; `reviewAndFixLoop`'s parallel reviewers each mint their
  * own conversation via `agent.chat()`.
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
  /** Run one autonomous turn against `session` and return its result, once it
    * has drained cleanly and the session is committed as resumable.
    *
    * `events` receives per-tool-use and per-message progress as the subprocess
    * runs. Defaults to a no-op listener for callers (typically tests) that
    * don't observe progress.
    *
    * `outputSchema`, when supplied, is the JSON Schema the final assistant
    * payload must conform to. Backends that enforce schemas natively (claude's
    * `--json-schema`) pass it to the CLI; others can ignore it. Either way the
    * drain withholds the closing turn as the structured payload — the caller
    * surfaces it via `OrcaEvent.StructuredResult` instead.
    *
    * The commit runs only after a clean drain, so a subprocess that crashed
    * before registering its session doesn't wedge the registry into resuming a
    * session that was never created. It throws on an unsafe wire id
    * ([[SessionSupport.commitAfterDrain]]). The cancel before the scope joins
    * reaches only what is still linked to the agent process; a subprocess
    * backend's environment-cookie sweep ([[SubprocessSpawn]]), run when the
    * scope ends, catches what detached.
    */
  final def runAutonomous(
      prompt: String,
      session: SessionId[B],
      config: AgentConfig,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): AgentResult[B] =
    val dispatch = enterTurn(session, config, events)
    supervised:
      val conv = open(
        TurnRequest(
          prompt,
          session,
          dispatch,
          ConversationMode.Autonomous,
          config,
          outputSchema,
          events
        )
      )
      try
        val result =
          Conversations.drainAutonomous(conv, config.autoApprove, events)
        sessions.commitAfterDrain(session, result.wireId)
        result
      finally conv.cancel()

  /** Run one interactive turn against `session`: `interaction` drives the live
    * conversation, and the session is registered once it returns.
    *
    * The conversation's assistant prose reaches `events` as
    * `OrcaEvent.AssistantMessage`, with a structured call's closing turn
    * withheld ([[Conversations.withholdInteractiveProse]]); every other event
    * goes to `interaction`. A cancelled or failed drive throws and registers
    * nothing — interactive turns aren't retried, and the next dispatch probes
    * what the backend actually holds. An unsafe wire id is logged and skipped
    * ([[SessionSupport.register]]), so the user's completed turn survives it.
    */
  final def runInteractive(
      prompt: String,
      session: SessionId[B],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String],
      events: OrcaListener,
      interaction: Interaction
  ): AgentResult[B] =
    val dispatch = enterTurn(session, config, events)
    supervised:
      val conv = Conversations.withholdInteractiveProse(
        open(
          TurnRequest(
            prompt,
            session,
            dispatch,
            ConversationMode.Interactive(displayPrompt),
            config,
            outputSchema,
            events
          )
        ),
        events
      )
      try
        val result = interaction.drive(conv)
        sessions.register(session, result.wireId)
        result
      finally conv.cancel()

  /** Start one turn and return it as a live [[Conversation]] whose forks run in
    * the caller's per-turn scope. The backend owns the subprocess (or server
    * stream) and event parsing; draining, driving, session commit and teardown
    * belong to [[runAutonomous]] / [[runInteractive]].
    *
    * `turn.mode` decides whether the turn can ask the user (`ask_user` is wired
    * on `Interactive` turns only). Whatever the backend allocates for the turn
    * is registered with the caller's scope ([[TurnResources]]), which also
    * releases it when `open` fails.
    */
  protected[orca] def open(turn: TurnRequest[B])(using Ox): Conversation[B]

  /** The turn-entry gate: refuse a closed backend, then settle this turn's
    * dispatch and give its enforcement notice.
    *
    * Per turn, not once per session: the first turn commits the session, so a
    * caller's corrective re-prompt dispatches as `Resumed` — a different
    * guarantee on codex, and hence possibly a different notice.
    */
  private def enterTurn(
      session: SessionId[B],
      config: AgentConfig,
      events: OrcaListener
  ): Dispatch[B] =
    checkNotClosed()
    val dispatch = sessions.dispatchFor(session)
    announceEnforcementShortfall(config, dispatch, events)
    dispatch

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
    * about to run as `dispatch` asked for a restriction this backend cannot
    * apply mechanically. Delegates to [[enforcementNotice]], which owns both
    * the wording and the "already said" bookkeeping.
    */
  private def announceEnforcementShortfall(
      config: AgentConfig,
      dispatch: Dispatch[B],
      events: OrcaListener
  ): Unit =
    enforcementNotice.announceShortfall(
      this,
      config,
      dispatch.asTurnDispatch,
      events
    )

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
    * forks). Called by the runtime when the run's resource scope ends, BEFORE
    * the flow's `supervised` scope joins its forks — a resource whose teardown
    * unblocks a non-interruptible read must be released here, not in a
    * `supervised` scope's `releaseAfterScope` (Ox runs those after the join).
    * Idempotent; default no-op.
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
    * [[runInteractive]] gate every turn; a caller that emits events before the
    * turn (the agent surface's `UserPrompt`) gates earlier still, so a dead
    * handle emits nothing.
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
