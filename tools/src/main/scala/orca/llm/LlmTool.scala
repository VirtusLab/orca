package orca.llm

/** An LLM adapter usable from flow scripts — the handle you call from a
  * `flow(...)` block (`claude`, `codex`, etc.). Two paths to invoke the model:
  *
  *   - **`autonomous`** — free-form text, no structured output, no JSON schema
  *     wrapping. The agent's reply is returned verbatim.
  *     `claude.autonomous.run` is the cheapest single-turn shape;
  *     `startSession` / `continueSession` keep a thread alive so subsequent
  *     turns share context.
  *   - **`resultAs[O]`** — fix the output type and obtain a call object that
  *     exposes both `autonomous` and `interactive` modes. Each mode in turn
  *     exposes `run` / `startSession` / `continueSession` (autonomous adds
  *     `run`, interactive doesn't because an interactive session without a
  *     session id is meaningless).
  *
  * The API never hides the autonomous-vs-interactive choice behind a default —
  * it's always visible at the call site as the leftmost segment after the tool
  * / call gateway.
  *
  * Parameterized by the concrete `BackendTag` so session ids and results carry
  * the backend identity at the type level.
  */
trait LlmTool[B <: BackendTag]:
  def name: String

  /** Free-form text autonomous calls. Use this when the agent's reply is prose
    * / code / anything that doesn't need to parse as a structured `O`. For
    * structured output (and the interactive-conversation path), use
    * [[resultAs]].
    */
  def autonomous: AutonomousTextCall[B]

  /** Fix the output type of a structured call and obtain a gateway with both
    * `autonomous` and `interactive` modes. `O` needs a `JsonData[O]` — `derives
    * JsonData` on a case class is the normal way to provide one.
    *
    * An `Announce[O]` is also required; the library's default given returns
    * `None` (no auto-announce), so callers don't need to do anything unless
    * they want a friendly summary on the channel. See [[Announce]].
    */
  def resultAs[O: JsonData: Announce]: LlmCall[B, O]

  def withConfig(config: LlmConfig): LlmTool[B]
  def withSystemPrompt(prompt: String): LlmTool[B]
  def withName(name: String): LlmTool[B]

  /** Return a sibling tool whose config has [[LlmConfig.readOnly]] flipped on
    * — claude maps this to `--permission-mode plan`, so Edit/Write/Bash are
    * unavailable to the agent. Preserves the rest of the tool's config
    * (model, system prompt, autoApprove). Used by planning helpers so
    * `claude.opus.withReadOnly` keeps the opus pin while gating writes.
    */
  def withReadOnly: LlmTool[B]

trait ClaudeTool extends LlmTool[BackendTag.ClaudeCode.type]:
  /** Pin the Claude model for subsequent calls, overriding `LlmConfig.model`.
    * Typical usage: `claude.haiku.autonomous.run("summarize this")` for a cheap
    * fast call.
    */
  def haiku: ClaudeTool
  def sonnet: ClaudeTool
  def opus: ClaudeTool

trait CodexTool extends LlmTool[BackendTag.Codex.type]:
  def mini: CodexTool

/** Free-form text autonomous calls — the `LlmTool.autonomous` shape. Three
  * methods cover the session-retention axis: `run` for one-shot, `startSession`
  * to keep the thread alive for follow-ups, `continueSession` to resume.
  */
trait AutonomousTextCall[B <: BackendTag]:
  def run(prompt: String, config: LlmConfig = LlmConfig.default): String
  def startSession(
      prompt: String,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], String)
  def continueSession(
      sessionId: SessionId[B],
      prompt: String,
      config: LlmConfig = LlmConfig.default
  ): String
