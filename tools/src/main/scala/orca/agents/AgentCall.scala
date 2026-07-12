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
    * human-readable form of `input` (the `AgentInput[I]` serialization) so
    * listeners can surface what's being asked; framework-internal callers that
    * produce near-identical prompts in quick succession pass `false` to keep
    * the event log focused. Other events (`ToolUse`, `StructuredResult`,
    * `TokensUsed`, etc.) fire regardless.
    */
  final def run[I: AgentInput](
      input: I,
      config: Option[AgentConfig] = None,
      emitPrompt: Boolean = true
  )(using orca.InStage): O =
    runWithSession(input, SessionId.fresh[B], config, emitPrompt)._2

  /** The session-threading door behind [[run]] and [[Chat]]: runs `input`
    * against `session` (continuing it if the backend already has it this run)
    * and returns the caller's session handle back with the output. Ephemeral —
    * no seeding, no wire-id persistence.
    */
  private[orca] def runWithSession[I: AgentInput](
      input: I,
      session: SessionId[B],
      config: Option[AgentConfig],
      emitPrompt: Boolean
  )(using orca.InStage): (SessionId[B], O)

/** Interactive structured calls — open a conversation the user can drive
  * (clarifying questions, refinements) before the agent produces the final
  * structured `O`. Same shape as the autonomous variant; continuation goes
  * through [[Chat]] (`agent.chat()`), never a `FlowSession` — a live human is
  * steering the turn, so there is no seed to replay on resume, which is why
  * durable interactive sessions don't exist.
  */
trait InteractiveAgentCall[B <: BackendTag, O]:
  /** One interactive structured turn on a fresh conversation. */
  final def run[I: AgentInput](
      input: I,
      config: Option[AgentConfig] = None
  )(using orca.InStage): O =
    runWithSession(input, SessionId.fresh[B], config)._2

  /** The session-threading door behind [[run]] and [[Chat]]. */
  private[orca] def runWithSession[I: AgentInput](
      input: I,
      session: SessionId[B],
      config: Option[AgentConfig]
  )(using orca.InStage): (SessionId[B], O)

/** Free-form text turns — the internal engine behind `Agent.run` and
  * [[Chat.run]] (the non-structured sibling of [[AutonomousAgentCall]]).
  * Ephemeral: no seeding, no wire-id persistence; `orca.FlowSession` layers the
  * durable protocol on top of this same door.
  */
trait AutonomousTextCall[B <: BackendTag]:
  /** Run the agent on `prompt` against `session` (continuing it if the backend
    * already has it this run); returns the caller's session handle back with
    * the output. `emitPrompt = false` suppresses the `OrcaEvent.UserPrompt`
    * (used by internal callers producing near-identical prompts in quick
    * succession); other events fire regardless.
    */
  private[orca] def runWithSession(
      prompt: String,
      session: SessionId[B],
      config: Option[AgentConfig],
      emitPrompt: Boolean
  )(using orca.InStage): (SessionId[B], String)

/** Default implementation of [[AgentCall]] for any backend.
  *
  * The trait splits into `autonomous` and `interactive` sibling objects so the
  * call site shows which mode it picked. This class wires both:
  *
  *   - The autonomous shape goes through `backend.runAutonomous` and shares a
  *     retry-with-corrective-prompt loop: if the response fails to parse as
  *     `O`, the next attempt's prompt includes the failed output and the parser
  *     error so the model can self-correct.
  *   - The interactive shape opens a [[orca.backend.Conversation]] via the
  *     backend and hands it to the supplied [[Interaction]] for rendering and
  *     user steering. No retry: the user is steering, and a parse failure on
  *     the final payload is more useful surfaced than silently relaunched.
  */
class DefaultAgentCall[B <: BackendTag, O](
    backend: AgentBackend[B],
    effectiveConfig: Option[AgentConfig] => AgentConfig,
    prompts: Prompts,
    events: OrcaListener,
    interaction: Interaction,
    /** Used as the `agent` axis on `OrcaEvent.TokensUsed` — the owning
      * `Agent.name`, its bare identity. The `model` axis is read from the
      * response (or the pinned config); this name is the always-present agent
      * identifier.
      */
    agentName: String,
    /** Used as the `role` axis on `OrcaEvent.TokensUsed` — the owning
      * `Agent.role`, e.g. `Some("reviewer")` for a review-loop run. `None` for
      * an ordinary agent.
      */
    agentRole: Option[String] = None
)(using jd: JsonData[O], announce: Announce[O])
    extends AgentCall[B, O]:

  private given sttp.tapir.Schema[O] = jd.schema
  private given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[O] =
    jd.codec

  /** Derived eagerly (not inside `runAutonomousWithRetry`/`runInteractiveOnce`)
    * so an unsupported output shape — e.g. a `Map[String, _]` field, which
    * `JsonSchemaGen` rejects — fails at `resultAs[O]` construction, before this
    * call's stage does anything (spawns a backend process, consumes a turn),
    * rather than surfacing remotely as codex/claude's opaque
    * `invalid_json_schema` after destructive stage work already ran. Also
    * collapses what was a redundant re-derivation on every `run()` call for the
    * same handle into a single one.
    */
  private val outputSchema: String = JsonSchemaGen[O]

  /** The use-after-close guard's second gate: `resultAs[O]` refuses
    * construction on a closed agent, but a gateway built BEFORE the flow ended
    * and stored across the close boundary would still reach the backend — this
    * per-call check (free, since the closed latch lives on the `backend` this
    * class already holds) closes that gap with the same user-facing message.
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
    )(using orca.InStage): (SessionId[B], O) =
      checkNotClosed()
      runAutonomousWithRetry(input, config, session, emitPrompt)

  val interactive: InteractiveAgentCall[B, O] = new InteractiveAgentCall[B, O]:
    private[orca] def runWithSession[I: AgentInput](
        input: I,
        session: SessionId[B],
        config: Option[AgentConfig]
    )(using orca.InStage): (SessionId[B], O) =
      checkNotClosed()
      runInteractiveOnce(input, config, session)

  /** Emit a `StructuredResult` event carrying the raw payload and the
    * `Announce[O]`-derived summary. `summary` is tri-state (documented on
    * [[orca.events.OrcaEvent.StructuredResult]]): text from a specific
    * `Announce[O]`; `Some("")` when a specific instance deliberately says
    * nothing (the call site narrates the outcome itself); `None` when no
    * specific instance exists — renderers then fall back to the raw payload so
    * the result stays visible. Non-terminal listeners (Slack, structured logs)
    * can carry `raw` through unchanged either way.
    */
  private def emitStructuredResult(raw: String, value: O): Unit =
    val summary = announce match
      case _: Announce.NoSpecific[?] => None
      case specific                  => specific.message(value).orElse(Some(""))
    events.onEvent(OrcaEvent.StructuredResult(raw, summary))

  /** THE retry policy — the only place in the framework that decides whether an
    * autonomous-turn failure gets retried: parse failures (corrective
    * re-prompt, same session resumed) and pre-spawn open failures (a fresh
    * spawn) are retried; [[AgentTurnFailed]] never is. Why reopening a locked
    * session id makes an `AgentTurnFailed` retry futile is owned by the
    * classifier, [[orca.backend.ForkedConversation.awaitResult]]. On a parse
    * failure the next attempt swaps the original prompt for a corrective one;
    * the returned session id is whichever attempt succeeded.
    */
  private def runAutonomousWithRetry[I](
      input: I,
      config: Option[AgentConfig],
      session: SessionId[B],
      emitPrompt: Boolean
  )(using ai: AgentInput[I]): (SessionId[B], O) =
    val serialized = ai.serialize(input)
    val effective = effectiveConfig(config)
    val initialPrompt = prompts.autonomous(serialized, outputSchema, effective)

    // Surface `serialized` (the human-readable input) rather than
    // `initialPrompt` (the schema-wrapped form the agent sees). Listeners
    // want the question, not the boilerplate that frames it. The retry
    // branch below emits its own UserPrompt so a parse failure still
    // shows what the follow-up turn was asked to fix.
    if emitPrompt then events.onEvent(OrcaEvent.UserPrompt(serialized))

    // Carries a parse failure into the next attempt's corrective prompt
    // (below: `lastFailure match { ... }` picks it up to build the retry
    // text). Method-scope var, sanctioned by the project's FP conventions
    // because its contract is entirely local: written only in the
    // `MalformedAgentOutputException` catch a few lines down, read only at
    // the top of the next `retry` iteration — single-threaded because
    // `retry` re-executes the block sequentially, never concurrently.
    var lastFailure: Option[FailedAttempt] = None

    // Never retry an `AgentTurnFailed` (see the classifier/policy scaladoc
    // above); every other throwable — parse failures and pre-spawn open
    // failures alike — is retryable.
    val retryConfig = RetryConfig(
      effective.retrySchedule,
      ResultPolicy.retryWhen[Throwable, (SessionId[B], O)](e =>
        !e.isInstanceOf[AgentTurnFailed]
      )
    )

    try
      retry(retryConfig):
        val promptText = lastFailure match
          case Some(f) =>
            val corrective = prompts.retry(f.response, f.parserError)
            if emitPrompt then events.onEvent(OrcaEvent.UserPrompt(corrective))
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
          // The stable client handle; result.wireId is the wire-side truth.
          (session, parsed)
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
      // Attribute the failure: name the agent and the size of this turn's
      // input, so "Prompt is too long" becomes actionable (which agent, how
      // big). The session's accumulated context is larger than this turn's
      // input — full prompts are captured by the debug log.
      case e: AgentTurnFailed =>
        throw new AgentTurnFailed(
          s"agent '$agentName' turn failed " +
            s"(this turn's input ≈${serialized.length} chars): ${e.getMessage}",
          e
        )

  /** Interactive variant. No retry: the user is steering the session and a
    * parse failure here means the session's final payload didn't match the
    * expected schema — surface it directly so the flow sees it rather than
    * silently relaunching the agent.
    */
  private def runInteractiveOnce[I](
      input: I,
      config: Option[AgentConfig],
      session: SessionId[B]
  )(using ai: AgentInput[I]): (SessionId[B], O) =
    val serialized = ai.serialize(input)
    val effective = effectiveConfig(config)
    val prompt = prompts.interactive(serialized, outputSchema, effective)
    // Per-turn structured-concurrency scope: `runInteractive` forks its workers
    // into this Ox, `drive` consumes them, and `cancel` (in the `finally`) tears
    // the conversation down before the scope joins — so a cancelled turn never
    // leaks the subprocess/forks. On cancel `drive` throws, skipping the
    // sessions.register / TokensUsed bookkeeping below, as before.
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
    // Codex mints its server thread id inside the drain (not at spawn);
    // surface it back to the backend so a follow-up call with the same
    // `session` can resume the right thread. No-op for backends whose
    // session id IS the client-supplied UUID (claude).
    backend.sessions.register(session, result.wireId)
    // TokensUsed emits on the normal path only. If the user cancels
    // mid-session, drive throws before this line — and the wire
    // protocols don't always carry partial usage, so there's nothing
    // authoritative to emit at cancel time.
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
    // The stable client handle; result.wireId is the wire-side truth.
    (session, parsed)

private case class FailedAttempt(response: String, parserError: String)
