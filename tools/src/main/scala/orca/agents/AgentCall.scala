package orca.agents

import orca.AgentTurnFailed
import orca.backend.{Interaction, AgentBackend}
import orca.events.{OrcaEvent, OrcaListener}
import orca.util.JsonSchemaGen
import ox.resilience.{ResultPolicy, RetryConfig, retry}

/** Structured-output gateway — obtained via `tool.resultAs[O]`. Splits the
  * autonomous-vs-interactive choice into two sibling objects so the call site
  * always shows which mode it picked.
  */
trait AgentCall[B <: BackendTag, O]:
  def autonomous: AutonomousAgentCall[B, O]
  def interactive: InteractiveAgentCall[B, O]

/** Autonomous structured calls — single agentic turn, no human in the loop.
  * `run` is a one-shot on a fresh, throwaway conversation; to continue a
  * conversation across calls, mint a [[Chat]] (`agent.chat()`) and go through
  * its `resultAs[O]` door instead.
  */
trait AutonomousAgentCall[B <: BackendTag, O]:
  /** One ephemeral structured turn on a fresh conversation. When `emitPrompt`
    * is true (the default), fires an `OrcaEvent.UserPrompt` carrying the
    * human-readable form of `input`; internal callers producing near-identical
    * prompts in quick succession pass `false` to keep the event log focused.
    * Other events (`ToolUse`, `TokensUsed`, etc.) fire regardless.
    */
  final def run[I: AgentInput](
      input: I,
      config: Option[AgentConfig] = None,
      emitPrompt: Boolean = true
  )(using orca.InStage): O =
    runWithSession(input, SessionId.fresh[B], config, emitPrompt)

  /** The session-threading door behind [[run]] and [[Chat]]: runs `input`
    * against `session`, continuing it if the backend already has it this run.
    * Ephemeral — no seeding, no wire-id persistence.
    */
  private[orca] def runWithSession[I: AgentInput](
      input: I,
      session: SessionId[B],
      config: Option[AgentConfig],
      emitPrompt: Boolean
  )(using orca.InStage): O

/** Interactive structured calls — open a conversation the user can drive
  * (clarifying questions, refinements) before the agent produces the final
  * structured `O`. Continuation goes through [[Chat]] (`agent.chat()`), never a
  * `FlowSession`: a live human is steering the turn, so there is no seed to
  * replay on resume — hence durable interactive sessions don't exist.
  */
trait InteractiveAgentCall[B <: BackendTag, O]:
  /** One interactive structured turn on a fresh conversation. */
  final def run[I: AgentInput](
      input: I,
      config: Option[AgentConfig] = None
  )(using orca.InStage): O =
    runWithSession(input, SessionId.fresh[B], config)

  /** The session-threading door behind [[run]] and [[Chat]]. */
  private[orca] def runWithSession[I: AgentInput](
      input: I,
      session: SessionId[B],
      config: Option[AgentConfig]
  )(using orca.InStage): O

/** Free-form text turns — the internal engine behind `Agent.run` and
  * [[Chat.run]] (the non-structured sibling of [[AutonomousAgentCall]]).
  * Ephemeral: no seeding, no wire-id persistence; `orca.FlowSession` layers the
  * durable protocol on top of this same door.
  */
private[orca] trait AutonomousTextCall[B <: BackendTag]:
  /** Run the agent on `prompt` against `session`, continuing it if the backend
    * already has it this run. `emitPrompt = false` suppresses the
    * `OrcaEvent.UserPrompt` (used by internal callers producing near-identical
    * prompts in quick succession); other events fire regardless.
    */
  private[orca] def runWithSession(
      prompt: String,
      session: SessionId[B],
      config: Option[AgentConfig],
      emitPrompt: Boolean
  )(using orca.InStage): String

/** Default implementation of [[AgentCall]] for any backend, wiring both modes:
  *
  *   - The autonomous shape goes through `backend.runAutonomous` with a
  *     retry-with-corrective-prompt loop: a response that fails to parse as `O`
  *     re-prompts with the failed output and parser error so the model can
  *     self-correct.
  *   - The interactive shape opens a [[orca.backend.Conversation]] and hands it
  *     to the supplied [[Interaction]] for rendering and user steering. No
  *     retry: a parse failure on the final payload is more useful surfaced than
  *     silently relaunched.
  */
class DefaultAgentCall[B <: BackendTag, O](
    backend: AgentBackend[B],
    effectiveConfig: Option[AgentConfig] => AgentConfig,
    prompts: Prompts,
    events: OrcaListener,
    interaction: Interaction,
    /** The `agent` axis on `OrcaEvent.TokensUsed` — the owning `Agent.name`.
      * The `model` axis is read from the response (or the pinned config).
      */
    agentName: String,
    /** The `role` axis on `OrcaEvent.TokensUsed` — the owning `Agent.role`,
      * e.g. `Some("reviewer")` for a review-loop run.
      */
    agentRole: Option[String] = None
)(using jd: JsonData[O], announce: Announce[O])
    extends AgentCall[B, O]:

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

  /** The use-after-close guard's second gate: `resultAs[O]` refuses
    * construction on a closed agent, but a gateway built before the flow ended
    * and stored across the close boundary would still reach the backend — this
    * per-call check closes that gap.
    */
  private def checkNotClosed(): Unit =
    if backend.isClosed then
      throw new orca.OrcaFlowException(AgentBackend.ClosedMessage)

  val autonomous: AutonomousAgentCall[B, O] = new AutonomousAgentCall[B, O]:
    private[orca] def runWithSession[I: AgentInput](
        input: I,
        session: SessionId[B],
        config: Option[AgentConfig],
        emitPrompt: Boolean
    )(using orca.InStage): O =
      checkNotClosed()
      runAutonomousWithRetry(input, config, session, emitPrompt)

  val interactive: InteractiveAgentCall[B, O] = new InteractiveAgentCall[B, O]:
    private[orca] def runWithSession[I: AgentInput](
        input: I,
        session: SessionId[B],
        config: Option[AgentConfig]
    )(using orca.InStage): O =
      checkNotClosed()
      runInteractiveOnce(input, config, session)

  /** Emit a `StructuredResult` event carrying the raw payload and the
    * `Announce[O]`-derived summary. `summary` is tri-state (see
    * [[orca.events.OrcaEvent.StructuredResult]]): text from a specific
    * `Announce[O]`; `Some("")` when a specific instance deliberately says
    * nothing; `None` when none exists — renderers then fall back to the raw
    * payload so the result stays visible.
    */
  private def emitStructuredResult(raw: String, value: O): Unit =
    val summary = announce match
      case _: Announce.NoSpecific[?] => None
      case specific                  => specific.message(value).orElse(Some(""))
    events.onEvent(OrcaEvent.StructuredResult(raw, summary))

  /** Fires once a session's first turn commits (ADR 0021 §8). Read
    * `backend.sessions.persistableWireId` directly (rather than through
    * `Agent.resumeWireId`, which needs an `Agent` instance this class doesn't
    * hold) — the same underlying lookup a `BaseAgent`-backed tool's
    * `resumeWireId` resolves to.
    */
  private def emitSessionCommitted(session: SessionId[B]): Unit =
    events.onEvent(
      OrcaEvent.SessionCommitted(
        backend.tag.wireName,
        session.value,
        backend.sessions.persistableWireId(session).map(_.value),
        agentName,
        agentRole
      )
    )

  /** THE retry policy — the only place that decides whether an autonomous-turn
    * failure gets retried: parse failures (corrective re-prompt, same session
    * resumed) and pre-spawn open failures (a fresh spawn) are retried;
    * [[AgentTurnFailed]] never is (see the classifier,
    * [[orca.backend.ForkedConversation.awaitResult]]).
    */
  private def runAutonomousWithRetry[I](
      input: I,
      config: Option[AgentConfig],
      session: SessionId[B],
      emitPrompt: Boolean
  )(using ai: AgentInput[I]): O =
    val serialized = ai.serialize(input)
    val effective = effectiveConfig(config)
    val initialPrompt = prompts.autonomous(
      serialized,
      outputSchema,
      effective,
      backend.structuredOutputMode
    )

    // Surface `serialized` (the human-readable input), not `initialPrompt` (the
    // schema-wrapped form the agent sees): listeners want the question.
    if emitPrompt then events.onEvent(OrcaEvent.UserPrompt(serialized))

    // Carries a parse failure into the next attempt's corrective prompt. Local
    // contract: written only in the `MalformedAgentOutputException` catch below,
    // read only at the top of the next `retry` iteration, which re-executes the
    // block sequentially — never concurrently.
    var lastFailure: Option[FailedAttempt] = None

    val retryConfig = RetryConfig(
      effective.retrySchedule,
      ResultPolicy.retryWhen[Throwable, O](e =>
        !e.isInstanceOf[AgentTurnFailed]
      )
    )

    val parsed =
      try
        retry(retryConfig):
          val promptText = lastFailure match
            case Some(f) =>
              val corrective = prompts.retry(f.response, f.parserError)
              if emitPrompt then
                events.onEvent(OrcaEvent.UserPrompt(corrective))
              corrective
            case None => initialPrompt
          val result = backend.runAutonomous(
            promptText,
            session,
            effective,
            events,
            outputSchema = Some(outputSchema)
          )
          events.onEvent(
            OrcaEvent.TokensUsed(
              agentName,
              result.model.orElse(effective.model),
              result.usage,
              agentRole
            )
          )
          try
            val parsed = ResponseParser.parse[O](result.output)
            emitStructuredResult(result.output, parsed)
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
      catch
        // Attribute the failure: name the agent and this turn's input size, so
        // "Prompt is too long" becomes actionable. The session's accumulated
        // context is larger than this turn's input.
        case e: AgentTurnFailed =>
          throw new AgentTurnFailed(
            s"agent '$agentName' turn failed " +
              s"(this turn's input ≈${serialized.length} chars): ${e.getMessage}",
            e
          )
    // The session commits on the FIRST successful turn; retries reuse the same
    // session, so one emission after the whole retry loop returns is correct
    // even when it took several backend turns to parse.
    emitSessionCommitted(session)
    parsed

  /** Interactive variant. No retry: the user is steering the session and a
    * parse failure here means the session's final payload didn't match the
    * expected schema — surface it directly so the flow sees it rather than
    * silently relaunching the agent.
    */
  private def runInteractiveOnce[I](
      input: I,
      config: Option[AgentConfig],
      session: SessionId[B]
  )(using ai: AgentInput[I]): O =
    val serialized = ai.serialize(input)
    val effective = effectiveConfig(config)
    val prompt = prompts.interactive(serialized, outputSchema, effective)
    // Per-turn structured-concurrency scope: `runInteractive` forks its workers
    // into this Ox, `drive` consumes them, and `cancel` (in the `finally`) tears
    // the conversation down before the scope joins — so a cancelled turn never
    // leaks the subprocess/forks. On cancel `drive` throws, skipping the
    // register / TokensUsed bookkeeping below.
    val result = ox.supervised:
      val conversation = backend.runInteractive(
        prompt,
        session,
        displayPrompt = serialized,
        effective,
        Some(outputSchema)
      )(using summon[ox.Ox])
      try interaction.drive(conversation)
      finally conversation.cancel()
    // Codex mints its server thread id inside the drain (not at spawn); surface
    // it back so a follow-up call with the same `session` resumes the right
    // thread. No-op for backends whose session id IS the client UUID (claude).
    backend.sessions.register(session, result.wireId)
    emitSessionCommitted(session)
    // Normal path only: on mid-session cancel `drive` throws before this line,
    // and the wire protocols don't always carry partial usage, so there's
    // nothing authoritative to emit at cancel time.
    events.onEvent(
      OrcaEvent.TokensUsed(
        agentName,
        result.model.orElse(effective.model),
        result.usage,
        agentRole
      )
    )
    val parsed = ResponseParser.parse[O](result.output)
    emitStructuredResult(result.output, parsed)
    parsed

private case class FailedAttempt(response: String, parserError: String)
