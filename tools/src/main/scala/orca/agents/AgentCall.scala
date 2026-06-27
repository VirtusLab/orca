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
  * Single method: pass a [[SessionId]] (typically from [[Agent.newSession]] or
  * the default fresh one); the library starts on the first call, resumes on
  * subsequent calls. Returns the (stable) session id.
  */
trait AutonomousAgentCall[B <: BackendTag, O]:
  /** Run the agent on `input`. When `emitPrompt` is true (the default), fires
    * an `OrcaEvent.UserPrompt` carrying the human-readable form of `input` (the
    * `AgentInput[I]` serialization) so listeners can surface what's being
    * asked; framework-internal callers that produce near-identical prompts in
    * quick succession (e.g. the per-task reviewer fan-out) pass `false` to keep
    * the event log focused. Other events (`ToolUse`, `StructuredResult`,
    * `TokensUsed`, etc.) fire regardless.
    */
  def run[I: AgentInput](
      input: I,
      session: SessionId[B] = SessionId.fresh[B],
      config: AgentConfig = AgentConfig.default,
      emitPrompt: Boolean = true
  )(using orca.InStage): (SessionId[B], O)

/** Interactive structured calls — open a conversation the user can drive
  * (clarifying questions, refinements) before the agent produces the final
  * structured `O`. Same shape as the autonomous variant.
  */
trait InteractiveAgentCall[B <: BackendTag, O]:
  def run[I: AgentInput](
      input: I,
      session: SessionId[B] = SessionId.fresh[B],
      config: AgentConfig = AgentConfig.default
  )(using orca.InStage): (SessionId[B], O)

/** Free-form text autonomous calls — the `Agent.autonomous` shape (the
  * non-structured sibling of [[AutonomousAgentCall]]). Single method: pass a
  * [[SessionId]] (typically from [[Agent.newSession]] or the default fresh one)
  * and the library starts the session on the first call, resumes it on
  * subsequent calls. Returns the (stable) session id so the caller can pass it
  * back unchanged.
  */
trait AutonomousTextCall[B <: BackendTag]:
  /** Run the agent on `prompt`. When `emitPrompt` is true (the default), fires
    * an `OrcaEvent.UserPrompt` carrying `prompt` so listeners can surface
    * what's being asked; framework-internal callers that produce many
    * near-identical prompts in quick succession (e.g. the per-task reviewer
    * fan-out) pass `false` to keep the event log focused. Other events
    * (`ToolUse`, `AssistantMessage`, `TokensUsed`, etc.) fire regardless.
    */
  def run(
      prompt: String,
      session: SessionId[B] = SessionId.fresh[B],
      config: AgentConfig = AgentConfig.default,
      emitPrompt: Boolean = true
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
    effectiveConfig: AgentConfig => AgentConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction,
    /** Used as the `agent` axis on `OrcaEvent.TokensUsed` — typically the
      * owning `Agent.name`, which carries the reviewer identity for tools
      * renamed via `withName`. The `model` axis is read from the response (or
      * the pinned config); this name is the always-present agent identifier.
      */
    agentName: String
)(using jd: JsonData[O], announce: Announce[O])
    extends AgentCall[B, O]:

  private given sttp.tapir.Schema[O] = jd.schema
  private given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[O] =
    jd.codec

  val autonomous: AutonomousAgentCall[B, O] = new AutonomousAgentCall[B, O]:
    def run[I: AgentInput](
        input: I,
        session: SessionId[B] = SessionId.fresh[B],
        config: AgentConfig = AgentConfig.default,
        emitPrompt: Boolean = true
    )(using orca.InStage): (SessionId[B], O) =
      runAutonomousWithRetry(input, config, session, emitPrompt)

  val interactive: InteractiveAgentCall[B, O] = new InteractiveAgentCall[B, O]:
    def run[I: AgentInput](
        input: I,
        session: SessionId[B] = SessionId.fresh[B],
        config: AgentConfig = AgentConfig.default
    )(using orca.InStage): (SessionId[B], O) =
      runInteractiveOnce(input, config, session)

  /** Emit a `StructuredResult` event carrying the raw payload and the
    * `Announce[O]`-derived summary (if any). The terminal listener renders
    * `summary` when present and skips otherwise; non-terminal listeners (Slack,
    * structured logs) can carry `raw` through unchanged.
    */
  private def emitStructuredResult(raw: String, value: O): Unit =
    events.onEvent(OrcaEvent.StructuredResult(raw, announce.message(value)))

  /** Autonomous retry loop used by `autonomous.run`. On a parse failure the
    * next attempt swaps the original prompt for a corrective one; the returned
    * session id is whichever one succeeded.
    */
  private def runAutonomousWithRetry[I](
      input: I,
      config: AgentConfig,
      session: SessionId[B],
      emitPrompt: Boolean
  )(using ai: AgentInput[I]): (SessionId[B], O) =
    val serialized = ai.serialize(input)
    val outputSchema = JsonSchemaGen[O]
    val initialPrompt = prompts.autonomous(serialized, outputSchema, config)
    val effective = effectiveConfig(config)

    // Surface `serialized` (the human-readable input) rather than
    // `initialPrompt` (the schema-wrapped form the agent sees). Listeners
    // want the question, not the boilerplate that frames it. The retry
    // branch below emits its own UserPrompt so a parse failure still
    // shows what the follow-up turn was asked to fix.
    if emitPrompt then events.onEvent(OrcaEvent.UserPrompt(serialized))

    // Threaded across retry attempts via closure so a parse failure can
    // steer the next attempt with the corrective prompt. Method-scope var
    // allowed by the project's FP conventions.
    var lastFailure: Option[FailedAttempt] = None

    // Retry parse failures (corrective re-prompt, resumed) and transient OPEN
    // failures (a fresh spawn), but never an `AgentTurnFailed` — a turn that
    // ran and failed (e.g. "Prompt is too long") leaves the session id
    // registered, so reopening it would only yield "already in use" / broken
    // pipe. Propagate it immediately with the real cause instead.
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
          workDir,
          events,
          outputSchema = Some(outputSchema)
        )
        events.onEvent(
          OrcaEvent.TokensUsed(
            agentName,
            result.model.orElse(effective.model),
            result.usage
          )
        )
        try
          val parsed = ResponseParser.parse[O](result.output)
          emitStructuredResult(result.output, parsed)
          (result.sessionId, parsed)
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
            s"(this turn's input ≈${serialized.length} chars): ${e.getMessage}"
        )

  /** Interactive variant. No retry: the user is steering the session and a
    * parse failure here means the session's final payload didn't match the
    * expected schema — surface it directly so the flow sees it rather than
    * silently relaunching the agent.
    */
  private def runInteractiveOnce[I](
      input: I,
      config: AgentConfig,
      session: SessionId[B]
  )(using ai: AgentInput[I]): (SessionId[B], O) =
    val serialized = ai.serialize(input)
    val outputSchema = JsonSchemaGen[O]
    val prompt = prompts.interactive(serialized, outputSchema, config)
    val effective = effectiveConfig(config)
    val conversation = backend.runInteractive(
      prompt,
      session,
      displayPrompt = serialized,
      effective,
      workDir,
      Some(outputSchema)
    )
    val result = interaction.drive(conversation)
    // Codex mints its server thread id inside the drain (not at spawn);
    // surface it back to the backend so a follow-up call with the same
    // `session` can resume the right thread. No-op for backends whose
    // session id IS the client-supplied UUID (claude).
    backend.registerSession(session, result.sessionId)
    // TokensUsed emits on the normal path only. If the user cancels
    // mid-session, drive throws before this line — and the wire
    // protocols don't always carry partial usage, so there's nothing
    // authoritative to emit at cancel time.
    events.onEvent(
      OrcaEvent.TokensUsed(
        agentName,
        result.model.orElse(effective.model),
        result.usage
      )
    )
    val parsed = ResponseParser.parse[O](result.output)
    emitStructuredResult(result.output, parsed)
    // Return the caller-supplied `session` (the stable handle), not
    // `result.sessionId` which for codex is the server thread id.
    (session, parsed)

private case class FailedAttempt(response: String, parserError: String)
