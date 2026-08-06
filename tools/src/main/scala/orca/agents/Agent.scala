package orca.agents

import orca.InStage
import orca.events.OrcaEvent
import orca.util.TextUtil
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

private val log = LoggerFactory.getLogger("orca.agents")

/** An LLM adapter usable from flow scripts — the handle you call from a
  * `flow(...)` block (`claude`, `codex`, etc.). Three rungs, by how long the
  * conversation must live:
  *
  *   - **`run(prompt)`** — one ephemeral free-text turn on a fresh
  *     conversation. `resultAs[O].{autonomous,interactive}.run(input)` is the
  *     structured sibling.
  *   - **`chat()`** — a fresh EPHEMERAL multi-turn conversation ([[Chat]]):
  *     in-run only, needs only `InStage` so it works inside a fork. Gone on
  *     crash/resume.
  *   - **`agent.session(name, seed)`** (a flow extension) — a DURABLE
  *     `orca.FlowSession` that survives a flow crash/resume: named, seeded, and
  *     persisted.
  *
  * Free text is always autonomous; `interactive` (a human steering the turn)
  * exists only under `resultAs[O]`, where the structured payload marks the
  * conversation's end.
  *
  * Parameterized by the concrete `BackendTag` so session ids and results carry
  * the backend identity at the type level.
  *
  * Several members below (`withRole`, `withCheapModel`, `withSelfManagedGit`,
  * …) default to a safe no-op `this`; `BaseAgent`-derived tools override every
  * one, and a custom `Agent` implementation must too, or the corresponding
  * feature is silently dropped.
  */
trait Agent[B <: BackendTag]:
  /** Label for this agent in the event stream (the `agent` axis of
    * `OrcaEvent.TokensUsed`). Defaults to the backend name; set it with
    * [[withName]] to distinguish roles in the cost report (e.g. "reviewer").
    */
  def name: String

  /** Role tag for this agent in the event stream — a second axis on
    * `OrcaEvent.TokensUsed` alongside [[name]]. The review loop sets
    * `Some("reviewer")` via [[withRole]] so `CostTracker` can subtotal reviewer
    * spend without baking a prefix into [[name]] (the identity a
    * session/selector keys off). Defaults to `None`. Unrelated to the wire
    * `role` field on a chat message (gemini's `Role`, pi's message role) — this
    * is a cost-attribution tag, not part of the conversation payload.
    */
  def role: Option[String] = None

  /** The free-text engine behind [[run]] and [[Chat.run]] — internal; the
    * public doors are `run` (one-shot) and `chat()` (multi-turn).
    */
  private[orca] def autonomous: AutonomousTextCall[B]

  /** One ephemeral free-text turn — a fresh conversation, discarded after the
    * reply. Use when the agent's reply is prose / code / anything that doesn't
    * need to parse as a structured `O` (that's [[resultAs]]). To keep talking
    * within the run, mint [[chat]]; to survive a crash/resume, use
    * `agent.session(name, seed)` (a durable `orca.FlowSession`).
    */
  final def run(
      prompt: String,
      config: Option[AgentConfig] = None,
      emitPrompt: Boolean = true
  )(using InStage): String =
    autonomous.runWithSession(prompt, SessionId.fresh[B], config, emitPrompt)

  /** Start a fresh EPHEMERAL multi-turn conversation — see [[Chat]]. In-run
    * only: nothing is persisted, so a crash/resume starts over. Needs only
    * `InStage`, so a chat can be minted and driven inside an `ox` fork.
    */
  final def chat(): Chat[B] = new Chat(this, SessionId.fresh[B])

  /** Adopt an existing conversation id as an EPHEMERAL chat — the escape hatch
    * for continuing a durable `FlowSession`'s conversation where its own doors
    * can't go (inside a fork): `agent.chat(coder.id)`. Turns run here are NOT
    * persisted — on crash/resume the durable side finds nothing recorded. One
    * live continuation at a time: concurrent turns against the same backend
    * conversation fail.
    */
  final def chat(continueFrom: SessionId[B]): Chat[B] =
    new Chat(this, continueFrom)

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

  /** Return a sibling tool tagged with `role` (see [[role]]) for the event
    * stream — used by the review loop to tag a reviewer's run without renaming
    * it.
    */
  def withRole(role: String): Agent[B] = this

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
    */
  def withCheapModel(model: Model): Agent[B] = this

  /** Best-effort one-line reply from the cheap model, for the runtime's own
    * incidental text (branch naming, default commit messages). Never throws — a
    * failure here must not break a flow, so any non-fatal error yields
    * `fallback`.
    *
    * Falling back is always announced, never silent: `purpose` is the noun
    * phrase naming what this one-shot was for ("commit message", "branch name")
    * and appears both in the WARN log line and in the `Step` event that tells
    * the user orca carried on with the default.
    */
  private[orca] def cheapOneShot(
      purpose: String,
      prompt: String,
      fallback: => String
  )(using InStage): String =
    try
      val line = Agent.payloadLine(cheap.withReadOnly.quietTextTurn(prompt))
      if line.isBlank then
        reportFallback(
          purpose = purpose,
          reason = "the model replied with nothing usable",
          cause = None
        )
        fallback
      else line
    catch
      case NonFatal(e) =>
        reportFallback(
          purpose = purpose,
          reason = TextUtil.throwableMessage(e, firstLineOnly = true),
          cause = Some(e)
        )
        fallback

  /** Announce a [[cheapOneShot]] fallback on both channels: WARN in the log
    * (with the throwable, when there is one, so the stack survives) and a
    * `Step` for the user, so the default value never appears unexplained.
    */
  private def reportFallback(
      purpose: String,
      reason: String,
      cause: Option[Throwable]
  ): Unit =
    cause match
      case Some(e) => log.warn(s"$purpose one-shot failed: $reason", e)
      case None    => log.warn("{} one-shot failed: {}", purpose, reason)
    emitEvent(
      OrcaEvent.Step(
        s"$purpose agent failed ($reason) — using the default $purpose instead"
      )
    )

  /** Publish an event on this agent's sink. No-op by default (tools without a
    * backend have no sink); `BaseAgent` routes it to the run's listener, which
    * is what makes [[cheapOneShot]]'s fallback visible to the user.
    */
  private[orca] def emitEvent(event: OrcaEvent): Unit = ()

  /** One autonomous text turn with the streaming display suppressed: no `▸`
    * prompt echo and — on `BaseAgent`-derived tools, which override this — no
    * `●` prose or `⏺` tool lines either (`TokensUsed` and `Error` events still
    * flow). For the runtime's internal turns ([[cheapOneShot]]), whose display
    * channel is the caller's own event, so streaming would show the text twice.
    */
  private[orca] def quietTextTurn(prompt: String)(using InStage): String =
    run(prompt, emitPrompt = false)

  /** Return a sibling tool that manages git itself — flips
    * [[AgentConfig.selfManagedGit]] on, suppressing the standing "runtime owns
    * git" rule the runtime otherwise injects (don't `git commit`/`push`/branch;
    * leave edits in the working tree). Use only for a flow that genuinely wants
    * the agent to drive git.
    */
  def withSelfManagedGit: Agent[B] = this

  /** The backend's session-durability capability, or `None` for tools without a
    * backend. The ONLY overridable session hook — the `willContinue` /
    * `resumeWireId` / `registerResumeWireId` trio below is `final`, implemented
    * uniformly through this, so a tool exposes its backend's whole
    * [[orca.backend.SessionSupport]] or nothing, never a partial mix.
    */
  private[orca] def sessionSupport: Option[orca.backend.SessionSupport[B]] =
    None

  /** This tool's backend tag, or `None` for tools without a backend
    * (lightweight stubs). Stamps [[orca.progress.SessionRecord.backend]] so a
    * resumed run's targeted rehydration knows which agent a session belongs to.
    */
  private[orca] def backendTag: Option[BackendTag] = None

  /** The model this tool's NEXT call will run, if pinned — either by the wiring
    * default (e.g. claude's Opus1M) or a `withModel`/tier accessor. `None` for
    * a backend that picks its own model (codex/pi defaults) or a tool without a
    * backend. The role-agents announcement reads this to show a resolved
    * default model the settings layer never pinned.
    */
  private[orca] def configuredModel: Option[Model] = None

  /** An opaque token identifying this tool's underlying backend INSTANCE, or
    * `None` for tools without a backend. `copyTool`-derived siblings
    * (`_.claude.opus`, `.withReadOnly`, …) share the same token because they
    * share the same backend object; independently-built backends of the same
    * kind get different tokens even though [[backendTag]] can't tell them
    * apart. [[orca.runner.DefaultFlowContext]] uses this to distinguish a
    * selector-derived sibling of a wired agent (safe) from a foreign agent
    * (event-blind, leaked past `close()`) — a plain `Agent eq Agent` check
    * can't, since the wrappers differ. `BaseAgent` overrides it to the shared
    * `AgentBackend.closedFlag` reference.
    *
    * Compared by REFERENCE (`eq`), never `==`: structural equality would
    * false-positive two independently-built backends as the same one.
    * Implementations must mint a unique token per backend instance.
    */
  private[orca] def backendIdentity: Option[AnyRef] = None

  /** Will the NEXT call on `session` continue an already-live conversation
    * (rather than open a fresh one that needs re-seeding)? The durable-session
    * runtime asks this before deciding whether to re-inject the seed + progress
    * preamble — see [[orca.backend.SessionSupport.willContinue]]. Returns
    * `false` (safe re-seed) when a concrete tool can't reach a backend.
    */
  final def willContinue(session: SessionId[B]): Boolean =
    sessionSupport.exists(_.willContinue(session))

  /** The [[WireSessionId]] to resume `client` ([[SessionId]], orca's stable
    * handle) against, or `None` if unknown or not durably resumable — equal to
    * `client` where the client id IS the wire id (claude, pi), a learned
    * server-thread id for codex/gemini/opencode, `None` for a backend whose
    * sessions don't outlive the run. The flow runtime reads this after a run to
    * persist it into the progress log.
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

  /** Release background resources this agent's backend owns. Delegates to
    * [[orca.backend.AgentBackend.close]]; a stub without a backend keeps the
    * no-op default. The runtime calls this from `DefaultFlowContext.close()`.
    */
  private[orca] def close(): Unit = ()

private[orca] object Agent:

  /** The single line to use from a cheap model's reply to a [[cheapOneShot]]
    * prompt. A fenced block, when the reply has one, wins over the surrounding
    * prose: cheap models routinely narrate first ("Looking at this diff, the
    * main changes are:") and put the actual answer inside the fence, so reading
    * top-down would take the narration. Falls back to the first non-empty,
    * non-fence line, and to `""` when the reply holds neither.
    */
  def payloadLine(text: String): String =
    val lines = text.linesIterator.map(_.trim).toList
    val fenced = lines
      .dropWhile(!_.startsWith("```"))
      .drop(1)
      .takeWhile(!_.startsWith("```"))
    fenced
      .find(_.nonEmpty)
      .orElse(lines.find(l => l.nonEmpty && !l.startsWith("```")))
      .getOrElse("")

/** Bare `claude` runs Opus with the 1M-token context window (the long-lived
  * implementer); the accessors below pin a specific tier, e.g.
  * `claude.haiku.run("summarize this")` for a cheap fast one-shot.
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

  /** Set the network tools added to the read-only `--tools` allowlist on
    * [[ToolSet.NetworkOnly]] turns. Bare claude tool names, e.g. `WebFetch`.
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
    * `codex.withModel(Model("gpt-5.6-sol"))`.
    */
  def withModel(model: Model): CodexAgent

  override protected def defaultCheap: CodexAgent = mini

/** OpenCode spans providers, so its model accessors are provider-prefixed (the
  * prefix keeps the vendor explicit at the call site). The openai accessors
  * follow OpenAI's durable capability tiers — sol (flagship), terra (balanced),
  * luna (efficiency) — the same way the anthropic accessors follow
  * opus/sonnet/haiku. [[withModel]] takes any `provider/model` id — including
  * self-hosted ones, e.g. `ollama/llama3.1`.
  */
trait OpencodeAgent extends Agent[BackendTag.Opencode.type]:
  def anthropicOpus: OpencodeAgent
  def anthropicSonnet: OpencodeAgent
  def anthropicHaiku: OpencodeAgent
  def openaiSol: OpencodeAgent
  def openaiTerra: OpencodeAgent
  def openaiLuna: OpencodeAgent

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
