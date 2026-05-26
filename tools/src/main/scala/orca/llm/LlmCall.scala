package orca.llm

import orca.backend.{Interaction, LlmBackend}
import orca.events.{OrcaEvent, OrcaListener}
import orca.util.JsonSchemaGen
import ox.resilience.retry

/** Structured-output gateway — obtained via `tool.resultAs[O]`. Splits the
  * autonomous-vs-interactive choice into two sibling objects so the call site
  * always shows which mode it picked.
  */
trait LlmCall[B <: BackendTag, O]:
  def autonomous: AutonomousLlmCall[B, O]
  def interactive: InteractiveLlmCall[B, O]

/** Autonomous structured calls — single agentic turn, no human in the loop.
  * `run` returns just `O`; `startSession` / `continueSession` retain the
  * session id alongside `O` so callers can continue the same context.
  */
trait AutonomousLlmCall[B <: BackendTag, O]:
  def run[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O
  def startSession[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], O)
  def continueSession[I: AgentInput](
      sessionId: SessionId[B],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O

/** Interactive structured calls — open a conversation the user can drive
  * (clarifying questions, refinements) before the agent produces the final
  * structured `O`. No `run` because an interactive call without a session id to
  * follow up on doesn't make sense — the conversation IS the session.
  */
trait InteractiveLlmCall[B <: BackendTag, O]:
  def startSession[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], O)
  def continueSession[I: AgentInput](
      sessionId: SessionId[B],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O

/** Default implementation of [[LlmCall]] for any backend.
  *
  * The trait splits into `autonomous` and `interactive` sibling objects so the
  * call site shows which mode it picked. This class wires both:
  *
  *   - The autonomous shape goes through `backend.runAutonomous` /
  *     `backend.continueAutonomous` and shares a retry-with-corrective-prompt
  *     loop: if the response fails to parse as `O`, the next attempt's prompt
  *     includes the failed output and the parser error so the model can
  *     self-correct.
  *   - The interactive shape opens a [[orca.backend.Conversation]] via the
  *     backend and hands it to the supplied [[Interaction]] for rendering and
  *     user steering. No retry: the user is steering, and a parse failure on
  *     the final payload is more useful surfaced than silently relaunched.
  */
class DefaultLlmCall[B <: BackendTag, O](
    backend: LlmBackend[B],
    effectiveConfig: LlmConfig => LlmConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction,
    /** Used as the `agent` axis on `OrcaEvent.TokensUsed` — typically the
      * owning `LlmTool.name`, which carries the reviewer identity for tools
      * renamed via `withName`. The `model` axis is read from the response (or
      * the pinned config); this name is the always-present agent identifier.
      */
    agentName: String
)(using jd: JsonData[O], announce: Announce[O])
    extends LlmCall[B, O]:

  private given sttp.tapir.Schema[O] = jd.schema
  private given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[O] =
    jd.codec

  val autonomous: AutonomousLlmCall[B, O] = new AutonomousLlmCall[B, O]:
    def run[I: AgentInput](input: I, config: LlmConfig = LlmConfig.default): O =
      runAutonomousWithRetry(input, config, resume = None)._2

    def startSession[I: AgentInput](
        input: I,
        config: LlmConfig = LlmConfig.default
    ): (SessionId[B], O) = runAutonomousWithRetry(input, config, resume = None)

    def continueSession[I: AgentInput](
        sessionId: SessionId[B],
        input: I,
        config: LlmConfig = LlmConfig.default
    ): O = runAutonomousWithRetry(input, config, resume = Some(sessionId))._2

  val interactive: InteractiveLlmCall[B, O] = new InteractiveLlmCall[B, O]:
    def startSession[I: AgentInput](
        input: I,
        config: LlmConfig = LlmConfig.default
    ): (SessionId[B], O) = runInteractiveOnce(input, config, resume = None)

    def continueSession[I: AgentInput](
        sessionId: SessionId[B],
        input: I,
        config: LlmConfig = LlmConfig.default
    ): O = runInteractiveOnce(input, config, resume = Some(sessionId))._2

  /** Emit a `StructuredResult` event carrying the raw payload and the
    * `Announce[O]`-derived summary (if any). The terminal listener renders
    * `summary` when present and skips otherwise; non-terminal listeners (Slack,
    * structured logs) can carry `raw` through unchanged.
    */
  private def emitStructuredResult(raw: String, value: O): Unit =
    events.onEvent(OrcaEvent.StructuredResult(raw, announce.message(value)))

  /** Autonomous retry loop used by autonomous.run / startSession /
    * continueSession. On a parse failure the next attempt swaps the original
    * prompt for a corrective one; the returned session id is whichever one
    * succeeded.
    */
  private def runAutonomousWithRetry[I](
      input: I,
      config: LlmConfig,
      resume: Option[SessionId[B]]
  )(using ai: AgentInput[I]): (SessionId[B], O) =
    val serialized = ai.serialize(input)
    val outputSchema = JsonSchemaGen[O]
    val initialPrompt = prompts.autonomous(serialized, outputSchema, config)
    val effective = effectiveConfig(config)

    // Threaded across retry attempts via closure so a parse failure can
    // steer the next attempt with the corrective prompt. Method-scope var
    // allowed by the project's FP conventions.
    var lastFailure: Option[FailedAttempt] = None

    retry(effective.retrySchedule):
      val promptText = lastFailure match
        case Some(f) => prompts.retry(f.response, f.parserError)
        case None    => initialPrompt
      val result = resume match
        case Some(sid) =>
          backend.continueAutonomous(sid, promptText, effective, workDir, events)
        case None => backend.runAutonomous(promptText, effective, workDir, events)
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

  /** Interactive variant. No retry: the user is steering the session and a
    * parse failure here means the session's final payload didn't match the
    * expected schema — surface it directly so the flow sees it rather than
    * silently relaunching the agent.
    */
  private def runInteractiveOnce[I](
      input: I,
      config: LlmConfig,
      resume: Option[SessionId[B]]
  )(using ai: AgentInput[I]): (SessionId[B], O) =
    val serialized = ai.serialize(input)
    val outputSchema = JsonSchemaGen[O]
    val prompt = prompts.interactive(serialized, outputSchema, config)
    val effective = effectiveConfig(config)
    val conversation = resume match
      case Some(sid) =>
        backend.continueInteractive(
          sid,
          prompt,
          displayPrompt = serialized,
          effective,
          workDir,
          Some(outputSchema)
        )
      case None =>
        backend.runInteractive(
          prompt,
          displayPrompt = serialized,
          effective,
          workDir,
          Some(outputSchema)
        )
    val result = interaction.drive(conversation)
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
    (result.sessionId, parsed)

private case class FailedAttempt(response: String, parserError: String)
