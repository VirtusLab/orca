package orca

/** An LLM adapter usable from flow scripts — the handle you call from a
  * `flow(...)` blocks (`claude`, `codex`, etc.) to run prompts, start or
  * continue sessions, and hand off interactive control. Parameterized by the
  * concrete `Backend` so session ids and results carry the backend identity at
  * the type level.
  */
trait LlmTool[B <: Backend]:
  def name: String

  /** Fix the output type of the call, then chain `.autonomous(...)` /
    * `.interactive(...)` / `.continueSession(...)` to actually invoke the
    * model. `O` needs a `JsonData[O]` — `derives JsonData` on a case class is
    * the normal way to provide one.
    *
    * An `Announce[O]` is also required; the library's default given returns an
    * empty string (no auto-announce), so callers don't need to do anything
    * unless they want a friendly summary on the channel. See [[Announce]].
    */
  def resultAs[O: JsonData: Announce]: LlmCall[B, O]

  /** One-shot autonomous call that takes a string and returns a string —
    * equivalent to `resultAs[String].autonomous(prompt, config)` without the
    * need for a JsonData instance. Use when the response is free-form text
    * rather than a structured value.
    */
  def ask(prompt: String, config: LlmConfig = LlmConfig.default): String

  /** Start a new session with a free-form text prompt and receive the
    * backend-assigned session id alongside a free-form text reply. Pairs with
    * `continueSession` below for pure-text multi-turn flows that don't need a
    * structured `O` — routing those through `resultAs[O].startSession` would
    * force the caller to invent an output type they immediately discard.
    */
  def startSession(
      prompt: String,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], String)

  /** Continue an existing session with a free-form text prompt and receive a
    * free-form text reply. The structured `resultAs[O].continueSession(...)`
    * path is for when the next turn's response should parse into `O`; this one
    * is for the more common "keep going, do X next" flow where the response is
    * prose or code, not JSON.
    */
  def continueSession(
      sessionId: SessionId[B],
      prompt: String,
      config: LlmConfig = LlmConfig.default
  ): String

  def withConfig(config: LlmConfig): LlmTool[B]
  def withSystemPrompt(prompt: String): LlmTool[B]

trait ClaudeTool extends LlmTool[Backend.ClaudeCode.type]:
  /** Returns a variant of this tool that pins the Claude model for subsequent
    * calls, overriding `LlmConfig.model`. Typical usage:
    * `claude.haiku.ask("summarize this")` for a cheap fast call.
    */
  def haiku: ClaudeTool
  def sonnet: ClaudeTool
  def opus: ClaudeTool

trait CodexTool extends LlmTool[Backend.Codex.type]:
  def mini: CodexTool

/** One configured LLM call of a given output type. Obtained via
  * `tool.resultAs[O]`; the returned value supports every invocation variant
  * (`autonomous`, session-based `startSession` / `continueSession`, and
  * `interactive` / `continueInteractive`) so callers can switch execution mode
  * without restating `O`.
  */
trait LlmCall[B <: Backend, O]:
  /** Run the call autonomously: a single turn, no session retained, no user
    * interaction.
    */
  def autonomous[I: AgentInput](
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
  def interactive[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], O)
  def continueInteractive[I: AgentInput](
      sessionId: SessionId[B],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O
