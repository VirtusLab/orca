package orca.agents

import orca.AgentTurnFailed
import orca.backend.{Interaction, AgentBackend}
import orca.events.{OrcaEvent, OrcaListener}
import orca.util.JsonSchemaGen
import ox.resilience.{ResultPolicy, RetryConfig, retry}

/** Structured-output gateway — obtained via `agent.resultAs[O]`. Splits the
  * autonomous-vs-interactive choice into two sibling objects so the call site
  * always shows which mode it picked:
  *
  *   - The autonomous shape goes through `backend.runAutonomous` with a
  *     retry-with-corrective-prompt loop: a response that fails to parse as `O`
  *     re-prompts with the failed output and parser error so the model can
  *     self-correct.
  *   - The interactive shape goes through `backend.runInteractive`, which hands
  *     the live conversation to the supplied [[Interaction]] for rendering and
  *     user steering. No retry: a parse failure on the final payload is more
  *     useful surfaced than silently relaunched.
  */
final class AgentCall[B <: BackendTag, O] private[orca] (
    backend: AgentBackend[B],
    config: AgentConfig,
    prompts: Prompts,
    events: OrcaListener,
    interaction: Interaction,
    /** The `agent` axis on `OrcaEvent.UnpricedTurn` — the owning `Agent.name`.
      * The `model` axis is read from the response (or the pinned config).
      */
    agentName: String,
    /** The `role` axis on `OrcaEvent.UnpricedTurn` — the owning `Agent.role`,
      * e.g. `Some("reviewer")` for a review-loop run.
      */
    agentRole: Option[String] = None
)(using jd: JsonData[O], announce: Announce[O]):

  private given sttp.tapir.Schema[O] = jd.schema
  private given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[O] =
    jd.codec

  /** Derived eagerly so an unsupported output shape (e.g. a `Map[String, _]`
    * field, which `JsonSchemaGen` rejects) fails at `resultAs[O]` construction,
    * before this call's stage spawns a backend process or consumes a turn —
    * rather than surfacing as codex/claude's opaque `invalid_json_schema` after
    * destructive stage work already ran.
    */
  private val outputSchema: String = JsonSchemaGen[O]

  /** Autonomous turns only: an interactive turn has a human steering one agent,
    * so its lines need no attribution.
    */
  private val attributedEvents: OrcaListener =
    OrcaListener.attributedTo(events, agentName)

  val autonomous: AutonomousAgentCall[B, O] = new AutonomousAgentCall(this)
  val interactive: InteractiveAgentCall[B, O] = new InteractiveAgentCall(this)

  private[agents] def runAutonomous[I: AgentInput](
      input: I,
      session: SessionId[B],
      sessionKey: Option[SessionKey],
      promptEvent: PromptEvent
  ): O =
    // `resultAs[O]` refuses construction on a closed agent, but a gateway
    // built before the flow ended and stored across the close boundary would
    // still reach the backend — this per-call check closes that gap.
    backend.checkNotClosed()
    runAutonomousWithRetry(input, session, sessionKey, promptEvent)

  /** `agent` follows the same rule as the turn's other display events: named on
    * an autonomous turn, `None` on an interactive one.
    */
  private def emitStructuredResult(
      raw: String,
      value: O,
      agent: Option[String]
  ): Unit =
    events.onEvent(
      OrcaEvent.StructuredResult(
        raw,
        Announce.announcement(announce, value),
        agent
      )
    )

  /** THE retry policy — the only place that decides whether an autonomous-turn
    * failure gets retried: parse failures (corrective re-prompt, same session
    * resumed) and pre-spawn open failures (a fresh spawn) are retried;
    * [[AgentTurnFailed]] never is (see the classifier,
    * [[orca.backend.DecodedTurn]]).
    */
  private def runAutonomousWithRetry[I](
      input: I,
      session: SessionId[B],
      sessionKey: Option[SessionKey],
      promptEvent: PromptEvent
  )(using ai: AgentInput[I]): O =
    val serialized = ai.serialize(input)
    val initialPrompt = prompts.autonomous(
      serialized,
      outputSchema,
      config,
      backend.structuredOutputMode
    )

    // Surface `serialized` (the human-readable input), not `initialPrompt` (the
    // schema-wrapped form the agent sees): listeners want the question.
    promptEvent.fire(events, serialized)

    val accounting = turnAccounting(session, sessionKey)

    // Carries a parse failure into the next attempt's corrective prompt. Local
    // contract: written only in the `MalformedAgentOutputException` catch below,
    // read only at the top of the next `attemptOnce` call, which `retry`
    // re-executes sequentially — never concurrently.
    var lastFailure: Option[FailedAttempt] = None

    // Counts the turns this call reported, not the attempts it started: an
    // attempt that dies before the model runs (a pre-spawn open failure,
    // retried below) reports no turn. Counting on entry would label the first
    // turn that is actually paid for as a retry. Same sequential-write contract
    // as `lastFailure` above.
    var turnsRecorded = 0

    /** One attempt: build this iteration's prompt (the initial one, or a
      * corrective re-prompt carrying the prior parse failure), run the turn,
      * and parse its output as `O`. A `MalformedAgentOutputException` records
      * itself as `lastFailure` for the next call before rethrowing, so `retry`
      * can drive a corrective re-prompt loop around repeated calls to this.
      */
    def attemptOnce(): O =
      // This attempt's turn index, whether it ends in a debit or a result.
      val thisTurn = turnsRecorded + 1
      val promptText = lastFailure match
        case Some(f) =>
          val corrective =
            prompts.retry(
              f.response,
              f.parserError,
              backend.structuredOutputMode
            )
          promptEvent.fire(events, corrective)
          corrective
        case None => initialPrompt
      val result =
        try
          backend.runAutonomous(
            promptText,
            session,
            config,
            attributedEvents,
            outputSchema = Some(outputSchema)
          )
        catch
          // Fires at most once per call — `AgentTurnFailed` is never retried.
          case e: AgentTurnFailed =>
            accounting.failedAfterModelRan(e.debit, thisTurn)
            throw e
      // Fire as soon as the backend drain commits — before the fallible
      // parse below — so a session that later exhausts its retries (parse
      // keeps failing) or throws on a subsequent attempt still gets
      // announced as durably resumable (ADR 0021 §8). Every attempt on this
      // session re-fires with the same payload; listeners dedup on
      // (backend, clientId, wireId) per the event's scaladoc.
      accounting.sessionCommitted()
      turnsRecorded = thisTurn
      accounting.succeeded(result, thisTurn)
      try
        val parsed = ResponseParser.parse[O](result.output)
        emitStructuredResult(result.output, parsed, agent = Some(agentName))
        parsed
      catch
        case e: MalformedAgentOutputException =>
          lastFailure = Some(
            FailedAttempt(
              response = e.rawOutput,
              parserError = e.shortCause
            )
          )
          throw e

    val retryConfig = RetryConfig(
      config.retrySchedule,
      ResultPolicy.retryWhen[Throwable, O](e =>
        !e.isInstanceOf[AgentTurnFailed]
      )
    )

    try retry(retryConfig)(attemptOnce())
    catch
      // Attribute the failure: name the agent and this turn's input size, so
      // "Prompt is too long" becomes actionable. The session's accumulated
      // context is larger than this turn's input.
      case e: AgentTurnFailed =>
        throw new AgentTurnFailed(
          s"agent '$agentName' turn failed " +
            s"(this turn's input ≈${serialized.length} chars): ${e.getMessage}",
          e.debit,
          e
        )

  /** Interactive variant. No retry: the user is steering the session and a
    * parse failure here means the session's final payload didn't match the
    * expected schema — surface it directly so the flow sees it rather than
    * silently relaunching the agent.
    */
  private[agents] def runInteractive[I](
      input: I,
      session: SessionId[B],
      sessionKey: Option[SessionKey]
  )(using ai: AgentInput[I]): O =
    val serialized = ai.serialize(input)
    val prompt = prompts.interactive(serialized, outputSchema, config)
    val accounting = turnAccounting(session, sessionKey)
    // On cancel the backend throws, skipping the session bookkeeping below;
    // `recording` still reports what the abandoned turn spent.
    val result = accounting.recording:
      backend.runInteractive(
        prompt,
        session,
        displayPrompt = serialized,
        config,
        Some(outputSchema),
        events,
        interaction
      )
    accounting.sessionCommitted()
    accounting.succeeded(result, TurnAccounting.OnlyTurn)
    val parsed = ResponseParser.parse[O](result.output)
    emitStructuredResult(result.output, parsed, agent = None)
    parsed

  private def turnAccounting(
      session: SessionId[B],
      sessionKey: Option[SessionKey]
  ): TurnAccounting[B] =
    new TurnAccounting[B](
      events = events,
      agentName = agentName,
      role = agentRole,
      backend = backend,
      session = session,
      sessionKey = sessionKey,
      pinned = config.model
    )

/** Autonomous structured calls — single agentic turn, no human in the loop.
  * `run` is a one-shot on a fresh, throwaway conversation; to continue a
  * conversation across calls, mint a [[Chat]] (`agent.chat()`) and go through
  * its `resultAs[O]` door instead.
  */
final class AutonomousAgentCall[B <: BackendTag, O] private[agents] (
    call: AgentCall[B, O]
):
  /** One ephemeral structured turn on a fresh conversation. The
    * `OrcaEvent.UserPrompt` it fires carries the human-readable form of
    * `input`; internal callers producing near-identical prompts in quick
    * succession pass `PromptEvent.Suppress` to keep the event log focused.
    */
  def run[I: AgentInput](
      input: I,
      promptEvent: PromptEvent = PromptEvent.Emit
  )(using orca.InStage): O =
    runWithSession(
      input,
      SessionId.fresh[B],
      sessionKey = None,
      promptEvent = promptEvent
    )

  /** The session-threading door behind [[run]] and [[Chat]]: runs `input`
    * against `session`, continuing it if the backend already has it this run.
    * Ephemeral — no seeding, no wire-id persistence.
    *
    * `sessionKey` is the durable key this session was minted under, carried
    * onto `OrcaEvent.SessionCommitted`; only `orca.FlowSession` has one.
    */
  private[orca] def runWithSession[I: AgentInput](
      input: I,
      session: SessionId[B],
      sessionKey: Option[SessionKey],
      promptEvent: PromptEvent
  )(using orca.InStage): O =
    call.runAutonomous(input, session, sessionKey, promptEvent)

/** Interactive structured calls — open a conversation the user can drive
  * (clarifying questions, refinements) before the agent produces the final
  * structured `O`. Continuation goes through [[Chat]] (`agent.chat()`), never a
  * `FlowSession`: a live human is steering the turn, so there is no seed to
  * replay on resume — hence durable interactive sessions don't exist.
  */
final class InteractiveAgentCall[B <: BackendTag, O] private[agents] (
    call: AgentCall[B, O]
):
  /** One interactive structured turn on a fresh conversation. */
  def run[I: AgentInput](input: I)(using orca.InStage): O =
    runWithSession(input, SessionId.fresh[B], sessionKey = None)

  /** The session-threading door behind [[run]] and [[Chat]]. `sessionKey` is
    * the durable key this session was minted under (see
    * [[AutonomousAgentCall.runWithSession]]).
    */
  private[orca] def runWithSession[I: AgentInput](
      input: I,
      session: SessionId[B],
      sessionKey: Option[SessionKey]
  )(using orca.InStage): O =
    call.runInteractive(input, session, sessionKey)

private case class FailedAttempt(response: String, parserError: String)
