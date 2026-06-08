package orca.llm

/** An LLM adapter usable from flow scripts — the handle you call from a
  * `flow(...)` block (`claude`, `codex`, etc.). Two paths to invoke the model:
  *
  *   - **`autonomous`** — free-form text, no structured output, no JSON schema
  *     wrapping. The agent's reply is returned verbatim.
  *   - **`resultAs[O]`** — fix the output type and obtain a call object that
  *     exposes both `autonomous` and `interactive` modes.
  *
  * Each mode has a single `run(input, session = …, config = …)` method that
  * always returns `(SessionId[B], output)`. Pre-allocate a session with
  * [[newSession]] and pass it across calls to keep one conversation alive; omit
  * the argument to get a fresh one-shot session per call.
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

  /** Return a sibling tool whose config has [[LlmConfig.readOnly]] flipped on —
    * claude maps this to `--permission-mode plan`, so Edit/Write/Bash are
    * unavailable to the agent. Preserves the rest of the tool's config (model,
    * system prompt, autoApprove). Used by planning helpers so
    * `claude.opus.withReadOnly` keeps the opus pin while gating writes.
    */
  def withReadOnly: LlmTool[B]

  /** Return a sibling tool that manages git itself — flips
    * [[LlmConfig.selfManagedGit]] on, suppressing the standing "runtime owns
    * git" rule the runtime otherwise injects (don't `git commit`/`push`/branch;
    * leave edits in the working tree). Use only for a flow that genuinely wants
    * the agent to drive git; the default keeps git runtime-owned.
    *
    * Unlike [[withReadOnly]] this carries a no-op default (returns `this`), so
    * a custom `LlmTool` that forgets to wire it simply keeps the safe
    * runtime-owns-git behaviour rather than silently granting the escape hatch.
    */
  def withSelfManagedGit: LlmTool[B] = this

  /** Mint a fresh session id you can pass to `.run(...)` across multiple calls.
    * The first call with this id starts the session; subsequent calls resume
    * it. Lets flow scripts hold a stable `val session = claude.newSession`
    * instead of threading a `var Option[SessionId]` through the loop.
    *
    * Default implementation generates a UUID via [[SessionId.fresh]]; backends
    * that need a different format (or eager server-side allocation) override.
    */
  def newSession: SessionId[B] = SessionId.fresh[B]

trait ClaudeTool extends LlmTool[BackendTag.ClaudeCode.type]:
  /** Pin the Claude model for subsequent calls, overriding `LlmConfig.model`.
    * Typical usage: `claude.haiku.autonomous.run("summarize this")._2` for a
    * cheap fast one-shot call (discard the returned session id).
    */
  def haiku: ClaudeTool
  def sonnet: ClaudeTool
  def opus: ClaudeTool

trait CodexTool extends LlmTool[BackendTag.Codex.type]:
  def mini: CodexTool

/** OpenCode spans providers, so its model accessors are provider-prefixed (the
  * prefix keeps the vendor explicit at the call site). [[withModel]] takes any
  * `provider/model` id — including self-hosted ones, e.g. `ollama/llama3.1`.
  */
trait OpencodeTool extends LlmTool[BackendTag.Opencode.type]:
  def anthropicOpus: OpencodeTool
  def anthropicSonnet: OpencodeTool
  def anthropicHaiku: OpencodeTool
  def openaiGpt5: OpencodeTool
  def openaiGpt5Codex: OpencodeTool
  def openaiGpt5Mini: OpencodeTool

  /** Pin any `provider/model` id (e.g. `ollama/llama3.1`, `myhost/qwen-coder`).
    */
  def withModel(providerModel: String): OpencodeTool

  /** Two-arg form of [[withModel]], e.g. `withModel("ollama", "llama3.1")`. The
    * default joins with `/`; the concrete tool validates the parts.
    */
  def withModel(provider: String, modelId: String): OpencodeTool =
    withModel(s"$provider/$modelId")

trait PiTool extends LlmTool[BackendTag.Pi.type]

trait GeminiTool extends LlmTool[BackendTag.Gemini.type]:
  /** Pin the cheap-and-fast Gemini Flash model for subsequent calls, overriding
    * `LlmConfig.model`. Bare `gemini` runs on Gemini Pro (pinned in the runtime
    * wiring); `gemini.flash` opts down for cheap one-shots.
    */
  def flash: GeminiTool

/** Free-form text autonomous calls — the `LlmTool.autonomous` shape. Single
  * method: pass a [[SessionId]] (typically from [[LlmTool.newSession]] or the
  * default fresh one) and the library starts the session on the first call,
  * resumes it on subsequent calls. Returns the (stable) session id so the
  * caller can pass it back unchanged.
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
      config: LlmConfig = LlmConfig.default,
      emitPrompt: Boolean = true
  ): (SessionId[B], String)
