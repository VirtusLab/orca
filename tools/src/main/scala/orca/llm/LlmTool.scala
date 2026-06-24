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

  /** Return a sibling tool whose config pins [[LlmConfig.tools]] to `tools` —
    * the capability tier (see [[ToolSet]]). The primitive behind
    * [[withReadOnly]] and [[withNetworkOnly]]; preserves the rest of the tool's
    * config (model, system prompt, autoApprove).
    */
  def withTools(tools: ToolSet): LlmTool[B]

  /** Sibling tool restricted to read-only tools ([[ToolSet.ReadOnly]]): no
    * edits, no shell. Used by planning and review helpers so e.g.
    * `claude.opus.withReadOnly` keeps the opus pin while gating writes.
    */
  def withReadOnly: LlmTool[B] = withTools(ToolSet.ReadOnly)

  /** Sibling tool restricted to reads plus network ([[ToolSet.NetworkOnly]]) —
    * for planner turns that must read an issue/PR. See [[ToolSet]] for the
    * per-backend no-edit guarantee (hard on most; prompt-only on pi / codex).
    */
  def withNetworkOnly: LlmTool[B] = withTools(ToolSet.NetworkOnly)

  /** A cheaper/faster variant of this model for incidental work (commit-message
    * summaries, reviewer selection, prompt shortening). Defaults to `this` when
    * a backend has no cheaper tier.
    */
  def cheap: LlmTool[B] = this

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

  /** Best-effort, non-destructive: is a live, resumable backend conversation
    * present for `session`? Delegates to the backend probe (R22). Returns
    * `false` by default — safe re-seed — when a concrete tool can't reach a
    * backend instance (e.g. lightweight stubs).
    */
  def sessionExists(session: SessionId[B]): Boolean = false

  /** The server-side session id mapped to `client`, or `None` if unknown.
    * Delegates to the backend's registry (R22); the flow runtime reads this
    * after a run to persist the client→server map into the progress log.
    * Returns `None` by default for tools without a backend (stubs).
    */
  def serverSessionId(client: SessionId[B]): Option[SessionId[B]] = None

  /** Record a learned client→server mapping in the backend's registry. The flow
    * runtime calls this on resume to rehydrate the map from the persisted log,
    * so `dispatchFor` resumes the right server thread and the probes target the
    * server id. No-op by default for tools without a backend (stubs).
    */
  def registerServerSession(
      client: SessionId[B],
      server: SessionId[B]
  ): Unit = ()

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
  def fable: ClaudeTool

  override def cheap: ClaudeTool = haiku

  /** Set the read-only network allowlist used on [[ToolSet.NetworkOnly]] turns
    * (claude `--allowedTools` syntax, e.g. `Bash(gh api:*)`, `WebFetch`).
    * Claude-specific, so it's here rather than on `LlmConfig`; defaults to
    * `ClaudeBackend.DefaultNetworkTools`. Pass it before handing the tool to a
    * planning helper: `claude.opus.withNetworkTools(Seq("WebFetch"))`.
    */
  def withNetworkTools(tools: Seq[String]): ClaudeTool

trait CodexTool extends LlmTool[BackendTag.Codex.type]:
  def mini: CodexTool

  override def cheap: CodexTool = mini

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

  override def cheap: OpencodeTool = anthropicHaiku

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

  override def cheap: GeminiTool = flash

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
  )(using orca.InStage): (SessionId[B], String)
