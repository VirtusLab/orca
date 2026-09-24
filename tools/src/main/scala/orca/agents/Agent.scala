package orca.agents

import orca.InStage
import orca.backend.{AgentBackend, AgentResult, Interaction}
import orca.events.{OrcaEvent, OrcaListener}
import orca.util.TextUtil
import org.slf4j.LoggerFactory
import ox.tap

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
  * the backend identity at the type level. Backend-specific model tiers
  * (`claude.opus`, `codex.mini`, …) are extensions on the tag-specific type,
  * provided by each backend's `*Agents` object.
  *
  * Every builder returns a sibling on the same backend instance, so the
  * siblings share its sessions, its enforcement notices and its close latch.
  */
final class Agent[B <: BackendTag] private (
    private val backend: AgentBackend[B],
    private[orca] val config: AgentConfig,
    prompts: Prompts,
    events: OrcaListener,
    interaction: Interaction,
    naming: AgentName,
    /** Role tag for this agent in the event stream — a second axis on
      * `OrcaEvent.UnpricedTurn` alongside [[name]]. The review loop sets
      * `Some("reviewer")` via [[withRole]] so `CostTracker` can subtotal
      * reviewer spend without baking a prefix into [[name]]. Unrelated to the
      * wire `role` field on a chat message — this is a cost-attribution tag,
      * not part of the conversation payload.
      */
    val role: Option[String]
):
  /** Label for this agent in the event stream (the `agent` axis of
    * `OrcaEvent.UnpricedTurn`). Set it with [[withName]] to tell agents apart
    * in the cost report.
    */
  def name: String = naming.label

  /** This agent named `label`, unless it was named with [[withName]]. */
  private[orca] def withDefaultNameReplacedBy(label: String): Agent[B] =
    naming match
      case AgentName.Default(_)  => withName(label)
      case AgentName.Explicit(_) => this

  /** One ephemeral free-text turn — a fresh conversation, discarded after the
    * reply. Use when the agent's reply is prose / code / anything that doesn't
    * need to parse as a structured `O` (that's [[resultAs]]). To keep talking
    * within the attempt, mint [[chat]]; to survive a crash/resume, use
    * `agent.session(name, seed)` (a durable `orca.FlowSession`).
    */
  def run(prompt: String, promptEvent: PromptEvent = PromptEvent.Emit)(using
      InStage
  ): String =
    runText(prompt, SessionId.fresh[B], sessionKey = None, promptEvent)

  /** Start a fresh EPHEMERAL multi-turn conversation — see [[Chat]]. In-run
    * only: nothing is persisted, so a crash/resume starts over. Needs only
    * `InStage`, so a chat can be minted and driven inside an `ox` fork.
    */
  def chat(): Chat[B] = new Chat(this, SessionId.fresh[B], ChatOrigin.Minted)

  /** Adopt an existing conversation id as an EPHEMERAL chat — how the library
    * continues a conversation it holds the id of (a durable session's
    * `session.chat`). Turns run here are NOT persisted and are not primed. One
    * live continuation at a time: concurrent turns against the same backend
    * conversation fail.
    */
  private[orca] def chat(continueFrom: SessionId[B]): Chat[B] =
    new Chat(this, continueFrom, ChatOrigin.Adopted)

  /** Fix the output type of a structured call and obtain a gateway with both
    * `autonomous` and `interactive` modes. `O` needs a `JsonData[O]` — `derives
    * JsonData` on a case class is the normal way to provide one.
    *
    * An `Announce[O]` is also required; the library's default given returns
    * `None` (no auto-announce), so callers don't need to do anything unless
    * they want a friendly summary on the channel. See [[Announce]].
    */
  def resultAs[O: JsonData: Announce]: AgentCall[B, O] =
    backend.checkNotClosed()
    new AgentCall[B, O](
      backend,
      config,
      prompts,
      events,
      interaction,
      agentName = name,
      agentRole = role
    )

  /** Sibling whose config pins [[AgentConfig.autoApprove]] to `autoApprove`. */
  def withAutoApprove(autoApprove: AutoApprove): Agent[B] =
    copy(config = config.copy(autoApprove = autoApprove))

  def withSystemPrompt(prompt: String): Agent[B] =
    copy(config = config.copy(systemPrompt = Some(prompt)))

  def withName(newName: String): Agent[B] =
    copy(naming = AgentName.Explicit(newName))

  /** Sibling tagged with `role` (see [[role]]) for the event stream — used by
    * the review loop to tag a reviewer's run without renaming it.
    */
  def withRole(newRole: String): Agent[B] = copy(role = Some(newRole))

  /** Sibling whose config pins [[AgentConfig.tools]] to `tools` — the
    * capability tier (see [[ToolSet]]). The primitive behind [[withReadOnly]]
    * and [[withNetworkOnly]].
    */
  def withTools(tools: ToolSet): Agent[B] =
    copy(config = config.copy(tools = tools))

  /** Sibling restricted to read-only tools ([[ToolSet.ReadOnly]]): no edits, no
    * shell. Used by planning and review helpers so e.g.
    * `claude.opus.withReadOnly` keeps the opus pin while gating writes.
    */
  def withReadOnly: Agent[B] = withTools(ToolSet.ReadOnly)

  /** Sibling restricted to reads plus network ([[ToolSet.NetworkOnly]]) — for
    * planner turns that must read an issue/PR. How strongly each backend blocks
    * edits varies; see [[Enforcement]].
    */
  def withNetworkOnly: Agent[B] = withTools(ToolSet.NetworkOnly)

  /** Pin the model for subsequent calls. Model ids are backend-specific; the
    * tier extensions (`claude.haiku`, `codex.mini`, …) name the common ones.
    */
  def withModel(model: Model): Agent[B] =
    copy(config = config.copy(model = Some(model)))

  /** A cheaper/faster variant of this model for incidental work (commit-message
    * summaries, reviewer selection, prompt shortening): the model pinned via
    * [[withCheapModel]] if one was set, otherwise the backend's built-in cheap
    * tier, otherwise this agent unchanged.
    */
  def cheap: Agent[B] =
    config.cheapModel
      .orElse(backend.cheapModel(config.model))
      .fold(this)(withModel)

  /** Pin the model that [[cheap]] resolves to, overriding the backend default.
    * Lets a flow specify both a leading and a cheap model, e.g.
    * `_.opencode.anthropicSonnet.withCheapModel(Model("anthropic/claude-haiku-4-5"))`.
    */
  def withCheapModel(model: Model): Agent[B] =
    copy(config = config.copy(cheapModel = Some(model)))

  /** Sibling that manages git itself — flips [[AgentConfig.selfManagedGit]] on,
    * suppressing the standing "runtime owns git" rule the runtime otherwise
    * injects (don't `git commit`/`push`/branch; leave edits in the working
    * tree). Use only for a flow that genuinely wants the agent to drive git.
    */
  def withSelfManagedGit: Agent[B] =
    copy(config = config.copy(selfManagedGit = true))

  /** Sibling whose config pins [[AgentConfig.networkTools]] to `tools`.
    * Backends that support network tools expose it as their own validated
    * builder.
    */
  private[orca] def withNetworkToolSet(tools: NetworkTools): Agent[B] =
    copy(config = config.copy(networkTools = Some(tools)))

  /** The free-text engine behind [[run]], [[Chat.run]] and `FlowSession.run`:
    * runs `prompt` against `session`, continuing it if the backend already has
    * it this attempt. `promptEvent` decides whether `OrcaEvent.UserPrompt`
    * fires; `sessionKey` is the durable key this session was minted under,
    * carried onto `OrcaEvent.SessionCommitted`.
    */
  private[orca] def runText(
      prompt: String,
      session: SessionId[B],
      sessionKey: Option[SessionKey],
      promptEvent: PromptEvent
  )(using InStage): String =
    backend.checkNotClosed()
    promptEvent.fire(events, prompt)
    val accounting = turnAccounting(session, sessionKey)
    val result =
      textTurn(
        prompt,
        session,
        accounting,
        OrcaListener.attributedTo(events, name)
      )
    accounting.sessionCommitted()
    result.output

  /** Best-effort one-line reply from the cheap model, for the runtime's own
    * incidental text (branch naming, default commit messages). The turn runs
    * under [[ToolSet.NoTools]]: it only transforms `prompt`, so repo or MCP
    * access would only give project instructions a way to derail it. Never
    * throws — a failure here must not break a flow, so any non-fatal error
    * yields `fallback`.
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
      val line = Agent.payloadLine(
        cheap.withTools(ToolSet.NoTools).quietTextTurn(prompt)
      )
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
    events.onEvent(
      OrcaEvent.Step(
        s"$purpose agent failed ($reason) — using the default $purpose instead"
      )
    )

  /** One autonomous text turn with the streaming display suppressed: no `▸`
    * prompt echo, no `●` prose or `⏺` tool lines (`UnpricedTurn` and `Error`
    * events still flow). For the runtime's internal turns ([[cheapOneShot]]),
    * whose display channel is the caller's own event, so streaming would show
    * the text twice.
    */
  private[orca] def quietTextTurn(prompt: String)(using InStage): String =
    val attributed = OrcaListener.attributedTo(events, name)
    val quietEvents: OrcaListener = (e: OrcaEvent) =>
      e match
        case _: OrcaEvent.AssistantMessage | _: OrcaEvent.ToolUse => ()
        case other => attributed.onEvent(other)
    val session = SessionId.fresh[B]
    textTurn(
      prompt,
      session,
      turnAccounting(session, sessionKey = None),
      quietEvents
    ).output

  /** One free-form turn, with its spend reported through `accounting` whether
    * it succeeds or fails after the model ran.
    */
  private def textTurn(
      prompt: String,
      session: SessionId[B],
      accounting: TurnAccounting[B],
      listener: OrcaListener
  ): AgentResult[B] =
    accounting
      .recording(backend.runAutonomous(prompt, session, config, listener))
      .tap(accounting.succeeded(_, TurnAccounting.OnlyTurn))

  private def turnAccounting(
      session: SessionId[B],
      sessionKey: Option[SessionKey]
  ): TurnAccounting[B] =
    new TurnAccounting[B](
      events = events,
      agentName = name,
      role = role,
      backend = backend,
      session = session,
      sessionKey = sessionKey,
      pinned = config.model
    )

  /** This agent's backend tag. Stamps `SessionRecord.backend`, so a later run
    * reuses a recorded session only on the same backend.
    */
  private[orca] def backendTag: B = backend.tag

  /** Whether `other` runs on this agent's backend INSTANCE — true for every
    * builder-derived sibling (`_.claude.opus`, `.withReadOnly`, …), false for
    * an independently built backend of the same kind.
    */
  private[orca] def sharesBackendWith(other: Agent[?]): Boolean =
    backend eq other.backend

  /** What the NEXT call on `session` does with the backend's conversation:
    * continue one it already holds, or open a fresh one that needs re-seeding —
    * see [[orca.backend.SessionSupport.dispatchFor]].
    */
  private[orca] def dispatchFor(
      session: SessionId[B]
  ): orca.backend.Dispatch[B] =
    backend.sessions.dispatchFor(session)

  /** The [[WireSessionId]] to resume `client` ([[SessionId]], orca's stable
    * handle) against, or `None` if unknown or not durably resumable — equal to
    * `client` where the client id IS the wire id (claude, pi), a learned
    * server-thread id for codex/gemini/opencode, `None` for a backend whose
    * sessions don't outlive the attempt. The flow runtime reads this after a
    * turn to persist it into the session store.
    */
  private[orca] def resumeWireId(
      client: SessionId[B]
  ): Option[WireSessionId[B]] =
    backend.sessions.persistableWireId(client)

  /** Record a resume wire id a previous attempt persisted for `client` — see
    * [[orca.backend.SessionSupport.rehydrate]]. `agent.session(name, seed)`
    * calls this when it reuses a recorded session.
    */
  private[orca] def rehydrateResumeWireId(
      client: SessionId[B],
      wireId: WireSessionId[B]
  ): Unit =
    backend.sessions.rehydrate(client, wireId)

  /** Mark this agent's backend as belonging to an ended flow, so later runs
    * through any handle sharing it are refused. The runtime calls this when the
    * flow run ends.
    */
  private[orca] def close(): Unit = backend.markClosed()

  private def copy(
      config: AgentConfig = config,
      naming: AgentName = naming,
      role: Option[String] = role
  ): Agent[B] =
    new Agent(backend, config, prompts, events, interaction, naming, role)

object Agent:

  /** An agent on `backend`, named `defaultName` until [[Agent.withName]] names
    * it explicitly.
    */
  private[orca] def apply[B <: BackendTag](
      backend: AgentBackend[B],
      config: AgentConfig,
      prompts: Prompts,
      events: OrcaListener,
      interaction: Interaction,
      defaultName: String
  ): Agent[B] =
    new Agent(
      backend,
      config,
      prompts,
      events,
      interaction,
      AgentName.Default(defaultName),
      role = None
    )

  /** The single line to use from a cheap model's reply to a
    * [[Agent.cheapOneShot]] prompt. Cheap models routinely write a preamble
    * before the answer ("Looking at this diff, the main changes are:", a note
    * about a tool they could not call), so reading top-down would take the
    * preamble. The first non-empty line of the first fenced block wins; without
    * a fence, the last non-empty, non-fence line; `""` when the reply holds
    * neither.
    */
  private[orca] def payloadLine(text: String): String =
    val lines = text.linesIterator.map(_.trim).toList
    val fenced = lines
      .dropWhile(!_.startsWith("```"))
      .drop(1)
      .takeWhile(!_.startsWith("```"))
    fenced
      .find(_.nonEmpty)
      .orElse(lines.findLast(l => l.nonEmpty && !l.startsWith("```")))
      .getOrElse("")

/** Where an [[Agent]]'s name came from: the backend default, or
  * [[Agent.withName]].
  */
private[agents] enum AgentName(val label: String):
  case Default(l: String) extends AgentName(l)
  case Explicit(l: String) extends AgentName(l)

type ClaudeAgent = Agent[BackendTag.ClaudeCode.type]
type CodexAgent = Agent[BackendTag.Codex.type]
type OpencodeAgent = Agent[BackendTag.Opencode.type]
type PiAgent = Agent[BackendTag.Pi.type]
type GeminiAgent = Agent[BackendTag.Gemini.type]
