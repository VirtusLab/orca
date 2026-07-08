package orca.agents

import orca.InStage

import scala.util.control.NonFatal

/** An LLM adapter usable from flow scripts — the handle you call from a
  * `flow(...)` block (`claude`, `codex`, etc.). Two paths to invoke the model:
  *
  *   - **`autonomous`** — free-form text, no structured output, no JSON schema
  *     wrapping. The agent's reply is returned verbatim.
  *   - **`resultAs[O]`** — fix the output type and obtain a call object that
  *     exposes both `autonomous` and `interactive` modes.
  *
  * Each mode has a single `run(input, session = …, config = …)` method that
  * always returns `(SessionId[B], output)`. Pass a `SessionId[B]` across calls
  * to keep one ephemeral conversation alive within a run, or omit it to get a
  * fresh one-shot session per call. For a DURABLE, resumable session (one that
  * survives a flow crash/resume, seeded and persisted), obtain an
  * `orca.FlowSession` with `agent.session(name, seed)` and call its
  * `run`/`resultAs` rather than feeding its `.id` into these raw doors.
  *
  * The API never hides the autonomous-vs-interactive choice behind a default —
  * it's always visible at the call site as the leftmost segment after the tool
  * / call gateway.
  *
  * Parameterized by the concrete `BackendTag` so session ids and results carry
  * the backend identity at the type level.
  */
trait Agent[B <: BackendTag]:
  /** Label for this agent in the event stream (the `agent` axis of
    * `OrcaEvent.TokensUsed`). Defaults to the backend name; set it with
    * [[withName]] to distinguish roles in the cost report (e.g. "reviewer").
    */
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
  def resultAs[O: JsonData: Announce]: AgentCall[B, O]

  def withConfig(config: AgentConfig): Agent[B]
  def withSystemPrompt(prompt: String): Agent[B]
  def withName(name: String): Agent[B]

  /** Return a sibling tool whose config pins [[AgentConfig.tools]] to `tools` —
    * the capability tier (see [[ToolSet]]). The primitive behind
    * [[withReadOnly]] and [[withNetworkOnly]]; preserves the rest of the tool's
    * config (model, system prompt, autoApprove).
    */
  def withTools(tools: ToolSet): Agent[B]

  /** Sibling tool restricted to read-only tools ([[ToolSet.ReadOnly]]): no
    * edits, no shell. Used by planning and review helpers so e.g.
    * `claude.opus.withReadOnly` keeps the opus pin while gating writes.
    */
  def withReadOnly: Agent[B] = withTools(ToolSet.ReadOnly)

  /** Sibling tool restricted to reads plus network ([[ToolSet.NetworkOnly]]) —
    * for planner turns that must read an issue/PR. See [[ToolSet]] for the
    * per-backend no-edit guarantee (hard on most; prompt-only on pi / codex).
    */
  def withNetworkOnly: Agent[B] = withTools(ToolSet.NetworkOnly)

  /** A cheaper/faster variant of this model for incidental work (commit-message
    * summaries, reviewer selection, prompt shortening). Returns the model
    * pinned via [[withCheapModel]] if one was set, otherwise the backend's
    * built-in cheap tier ([[defaultCheap]]).
    */
  def cheap: Agent[B] = defaultCheap

  /** The backend's built-in cheap variant (claude→haiku, codex→mini, …), before
    * any [[withCheapModel]] override; `this` when a backend has no cheaper
    * tier. Backends override this — flow code calls [[cheap]].
    */
  protected def defaultCheap: Agent[B] = this

  /** Pin the model that [[cheap]] resolves to, overriding the backend default.
    * Lets a flow specify both a leading and a cheap model, e.g.
    * `_.opencode.anthropicSonnet.withCheapModel(Model("anthropic/claude-haiku-4-5"))`.
    *
    * On an `Agent` that doesn't override this, the call is a SILENT NO-OP —
    * `this` is returned unchanged and the pin is dropped. `BaseAgent`-derived
    * tools (every real backend) override it; a custom `Agent` implementation
    * must override it too, or a caller's `.withCheapModel(...)` is silently
    * ignored and [[cheap]] keeps resolving to [[defaultCheap]].
    */
  def withCheapModel(model: Model): Agent[B] = this

  /** Best-effort one-line reply from the cheap model, for the runtime's own
    * incidental text (branch naming, default commit messages). Runs `prompt` on
    * `cheap.withReadOnly` (no prompt echo), returns the first non-blank line
    * trimmed — or `fallback` if the reply is empty or the call fails for any
    * non-fatal reason (markdown fence lines are skipped). Never throws: these
    * calls must never break a flow. `private[orca]` — internal; flow scripts
    * use `cheap.autonomous.run(...)` directly if they want a one-off cheap
    * call.
    */
  private[orca] def cheapOneShot(prompt: String, fallback: => String)(using
      InStage
  ): String =
    try
      val (_, text) =
        cheap.withReadOnly.autonomous.run(prompt, emitPrompt = false)
      val firstLine = text.linesIterator
        .map(_.trim)
        .filterNot(_.startsWith("```"))
        .find(_.nonEmpty)
        .getOrElse("")
      if firstLine.isBlank then fallback else firstLine
    catch case NonFatal(_) => fallback

  /** Return a sibling tool that manages git itself — flips
    * [[AgentConfig.selfManagedGit]] on, suppressing the standing "runtime owns
    * git" rule the runtime otherwise injects (don't `git commit`/`push`/branch;
    * leave edits in the working tree). Use only for a flow that genuinely wants
    * the agent to drive git; the default keeps git runtime-owned.
    *
    * Unlike [[withReadOnly]] this carries a no-op default (returns `this`), so
    * a custom `Agent` that forgets to wire it simply keeps the safe
    * runtime-owns-git behaviour rather than silently granting the escape hatch.
    *
    * On an `Agent` that doesn't override this, the call is a SILENT NO-OP —
    * `this` is returned unchanged. This one is deliberate (a fail-safe, not a
    * bug): `BaseAgent`-derived tools override it to actually flip the flag; a
    * custom `Agent` implementation must override it too if it wants callers'
    * `.withSelfManagedGit` to take effect.
    */
  def withSelfManagedGit: Agent[B] = this

  /** The backend's session-durability capability, or `None` for tools without a
    * backend (lightweight stubs). The ONLY overridable session hook — the
    * `willContinue` / `resumeWireId` / `registerResumeWireId` trio below is
    * `final`, implemented uniformly through this. A concrete tool exposes its
    * backend's whole [[orca.backend.SessionSupport]] or nothing, so it cannot
    * reflect one session operation while silently defaulting the others.
    */
  private[orca] def sessionSupport: Option[orca.backend.SessionSupport[B]] =
    None

  /** This tool's backend tag, or `None` for tools without a backend
    * (lightweight stubs). Used to stamp [[orca.progress.SessionRecord.backend]]
    * so a resumed run's targeted rehydration knows which agent a session
    * belongs to. `BaseAgent` overrides this to `Some(backend.tag)`; a concrete
    * tool built directly on `Agent` (no backend) keeps the `None` default.
    */
  private[orca] def backendTag: Option[BackendTag] = None

  /** An opaque token identifying this tool's underlying backend INSTANCE (not
    * just its runtime tag/type), or `None` for tools without a backend
    * (lightweight stubs). Two independently-built backends of the same kind
    * (e.g. two `ClaudeCode` backends from separate `AgentWiring`s) get
    * different tokens even though [[backendTag]] can't tell them apart;
    * `copyTool`-derived siblings (`_.claude.opus`, `.withReadOnly`, …) share
    * the SAME token because they share the SAME backend object. Used by
    * [[orca.runner.DefaultFlowContext]] to tell a selector-derived sibling of a
    * wired agent (safe — same backend, same events, same close latch) apart
    * from an agent built against a genuinely different backend (foreign —
    * event-blind, leaked past `close()`), without false-positiving on the
    * former the way a plain `Agent eq Agent` check would (complexity-review-2
    * 10.1). `BaseAgent` overrides this to the shared `AgentBackend.closedFlag`
    * reference — already unique per backend instance, and deliberately shared
    * across sibling backend instances that opt into it (e.g. claude's
    * `withNetworkTools`), for exactly this identity purpose.
    */
  private[orca] def backendIdentity: Option[AnyRef] = None

  /** Will the NEXT call on `session` continue an already-live conversation
    * (rather than open a fresh one that needs re-seeding)? The durable-session
    * runtime asks this before deciding whether to re-inject the seed + progress
    * preamble. Differs from [[orca.backend.SessionSupport.exists]] only for
    * ephemeral backends (pi), where a session with no durable transcript is
    * nonetheless a live in-process continuation — see
    * [[orca.backend.SessionSupport.willContinue]]. Returns `false` — safe
    * re-seed — when a concrete tool can't reach a backend.
    */
  final def willContinue(session: SessionId[B]): Boolean =
    sessionSupport.exists(_.willContinue(session))

  /** The wire id to resume `client` against, or `None` if unknown (or the
    * backend's sessions aren't durably resumable). `client` is orca's stable
    * handle ([[SessionId]]); the result is the [[WireSessionId]] the backend
    * actually resumes against — equal to `client` where the client id IS the
    * wire id (claude), a learned server-thread id for codex/gemini/opencode,
    * `None` for pi (ephemeral sessions). The flow runtime reads this after a
    * run to persist the resume wire id into the progress log.
    */
  final def resumeWireId(client: SessionId[B]): Option[WireSessionId[B]] =
    sessionSupport.flatMap(_.persistableWireId(client))

  /** Record a resume wire id for `client` in the backend's registry. The flow
    * runtime calls this on resume to rehydrate the map from the persisted log,
    * so `dispatchFor` resumes against the right wire id and the probes target
    * it. No-op when there is no backend (stubs).
    */
  final def registerResumeWireId(
      client: SessionId[B],
      wireId: WireSessionId[B]
  ): Unit =
    sessionSupport.foreach(_.register(client, wireId))

  /** Mint a fresh, unrecorded session id — used by the runtime for ephemeral
    * one-off conversations (e.g. each reviewer's own turn). NOT resume-aware:
    * it isn't recorded in the progress log, so a re-run mints a different id.
    * Flow scripts that need a session to survive restarts use
    * `agent.session(name, seed)` instead, which keys off the log.
    * `private[orca]` — internal.
    *
    * Default implementation generates a UUID via [[SessionId.fresh]]; backends
    * that need a different format (or eager server-side allocation) override.
    */
  private[orca] def newSession: SessionId[B] = SessionId.fresh[B]

  /** Release background resources this agent's backend owns. Delegates to
    * [[orca.backend.AgentBackend.close]]; a lightweight stub built directly on
    * `Agent` (no backend) keeps the no-op default. `private[orca]` — the
    * runtime calls this from `DefaultFlowContext.close()`; flow scripts never
    * call it directly.
    */
  private[orca] def close(): Unit = ()

/** Bare `claude` runs Opus with the 1M-token context window (the long-lived
  * implementer); the accessors below pin a specific tier, e.g.
  * `claude.haiku.autonomous.run("summarize this")._2` for a cheap fast
  * one-shot.
  */
trait ClaudeAgent extends Agent[BackendTag.ClaudeCode.type]:
  /** Pin the Claude model for subsequent calls, overriding `AgentConfig.model`.
    */
  def haiku: ClaudeAgent
  def sonnet: ClaudeAgent
  def opus: ClaudeAgent
  def fable: ClaudeAgent

  /** Pin any Claude model id beyond the named tiers, e.g.
    * `claude.withModel(Model("claude-opus-4-1-some-snapshot"))`.
    */
  def withModel(model: Model): ClaudeAgent

  override protected def defaultCheap: ClaudeAgent = haiku

  /** Set the read-only network allowlist used on [[ToolSet.NetworkOnly]] turns
    * (claude `--allowedTools` syntax, e.g. `Bash(gh api:*)`, `WebFetch`).
    * Claude-specific, so it's here rather than on `AgentConfig`; defaults to
    * `ClaudeBackend.DefaultNetworkTools`. Pass it before handing the tool to a
    * planning helper: `claude.opus.withNetworkTools(Seq("WebFetch"))`.
    */
  def withNetworkTools(tools: Seq[String]): ClaudeAgent

/** Bare `codex` runs the installed `codex-cli`'s default model; `codex.mini`
  * opts down to the cheap tier, and `codex.withModel(Model("..."))` pins any
  * other id the CLI offers.
  */
trait CodexAgent extends Agent[BackendTag.Codex.type]:
  def mini: CodexAgent

  /** Pin any codex model the installed `codex-cli` offers, beyond `mini` — e.g.
    * `codex.withModel(Model("gpt-5.4-pro"))`.
    */
  def withModel(model: Model): CodexAgent

  override protected def defaultCheap: CodexAgent = mini

/** OpenCode spans providers, so its model accessors are provider-prefixed (the
  * prefix keeps the vendor explicit at the call site). [[withModel]] takes any
  * `provider/model` id — including self-hosted ones, e.g. `ollama/llama3.1`.
  */
trait OpencodeAgent extends Agent[BackendTag.Opencode.type]:
  def anthropicOpus: OpencodeAgent
  def anthropicSonnet: OpencodeAgent
  def anthropicHaiku: OpencodeAgent
  def openaiGpt5: OpencodeAgent
  def openaiGpt5Codex: OpencodeAgent
  def openaiGpt5Mini: OpencodeAgent

  /** Base cheap variant is anthropic haiku; [[DefaultOpencodeAgent]] overrides
    * this to match the leading provider, so incidental work on an openai-led
    * tool doesn't pull in a second provider's auth.
    */
  override protected def defaultCheap: OpencodeAgent = anthropicHaiku

  /** Pin any `provider/model` id (e.g. `ollama/llama3.1`, `myhost/qwen-coder`).
    */
  def withModel(providerModel: String): OpencodeAgent

  /** Two-arg form of [[withModel]], e.g. `withModel("ollama", "llama3.1")`. The
    * default joins with `/`; the concrete tool validates the parts.
    */
  def withModel(provider: String, modelId: String): OpencodeAgent =
    withModel(s"$provider/$modelId")

trait PiAgent extends Agent[BackendTag.Pi.type]:
  /** Pin a pi model id; pi otherwise selects the model via its own CLI config.
    */
  def withModel(model: Model): PiAgent

trait GeminiAgent extends Agent[BackendTag.Gemini.type]:
  /** Pin the cheap-and-fast Gemini Flash model for subsequent calls, overriding
    * `AgentConfig.model`. Bare `gemini` runs on Gemini Pro (pinned in the
    * runtime wiring); `gemini.flash` opts down for cheap one-shots.
    */
  def flash: GeminiAgent

  /** Pin any Gemini model id beyond `flash`, e.g.
    * `gemini.withModel(Model("gemini-2.5-pro"))`.
    */
  def withModel(model: Model): GeminiAgent

  override protected def defaultCheap: GeminiAgent = flash
