package orca

import orca.events.OrcaEvent
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.agents.{
  Agent,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  OpencodeAgent,
  PiAgent
}

import scala.annotation.implicitNotFound

/** Ambient context a flow script operates in. Bundles every tool the top- level
  * accessors (`claude`, `codex`, `opencode`, `pi`, `gemini`, `git`, `gh`, `fs`)
  * resolve against, the user's positional prompt (`userPrompt`), and the event
  * sink (`emit`) that stage/fail/fixLoop and the library's internals publish
  * to.
  *
  * One is built per `flow(...)` invocation — flow scripts don't normally
  * instantiate `FlowContext` directly, just call the accessors inside a
  * `flow(args): ...` block and let Scala 3's context functions resolve the
  * given instance.
  */
@implicitNotFound(
  "the flow tools (`claude`/`codex`/`git`/`gh`/`fs`/…), `display`, and `fail` are only available inside a `flow(...)` body. Wrap this code in `flow(OrcaArgs(args), _.claude): ...`."
)
trait FlowContext:
  /** Backend tag of the leading agent. You never write this — it's the backend
    * of the agent your `flow(...)` selector picked, and it's why `agent` is
    * precisely typed (`Agent[LeadB]`) so sessions thread. (A type *member*
    * rather than a parameter, so `FlowContext` stays unparametrised — every
    * `using FlowContext` site is unaffected; the runtime captures the concrete
    * tag here at construction, with `flow` generic over it, inferred from the
    * selector.)
    *
    * '''Helper authoring:''' the path-dependent `agent: Agent[ctx.LeadB]`
    * accessor is convenient in a straight-line `flow(...)` body, where every
    * reference resolves against the same `using FlowContext` in scope — but it
    * stops working the moment you factor code into a helper *function*.
    * `ctx1.LeadB` and `ctx2.LeadB` from two different `using FlowContext`
    * parameters are different types to the compiler even when they're the same
    * backend at runtime, so a helper that takes `Agent[ctx.LeadB]` in one
    * parameter and tries to combine it with `SessionId[ctx.LeadB]` from another
    * can't unify them. Two ways out, both used by the library's own helpers:
    *
    *   - Take an explicit `[B <: BackendTag]` type parameter and type the
    *     helper's own parameters against it — `B` is then a genuine type
    *     variable the caller instantiates once, not a path into someone else's
    *     context. See [[orca.review.reviewAndFixLoop]]`(coderSession:
    *     FlowSession[B], ...)`, whose single [[orca.FlowSession]] bundles the
    *     agent and its session so `B` is pinned once at the call site.
    *   - Bundle the agent's session with its result as a single
    *     [[orca.plan.Sessioned]]`[B, A]` value, so callers pass one thing
    *     instead of two that have to agree on `B`. See `Plan.autonomous.*` /
    *     `Plan.interactive.*`.
    */
  type LeadB <: BackendTag

  /** The leading agent. Reference it in a body instead of a concrete accessor
    * (`claude`/`codex`) so the flow is backend-agnostic — switch the `flow`
    * selector and the whole flow follows. A session from `agent.session`
    * threads into `session.run` and the reviewers because [[LeadB]] pins the
    * backend.
    */
  def agent: Agent[LeadB]
  def claude: ClaudeAgent
  def codex: CodexAgent
  def opencode: OpencodeAgent
  def pi: PiAgent
  def gemini: GeminiAgent
  def git: GitTool
  def gh: GitHubTool
  def fs: FsTool
  def userPrompt: String
  def emit(event: OrcaEvent): Unit

  /** Exactly-once error reporting: the runtime marks a throwable here when it
    * publishes an `OrcaEvent.Error` for it, and every enclosing frame (nested
    * stages, the flow boundary) checks before re-reporting. Identity-based
    * (`eq`) — the mark travels with the object, not its type or message, so
    * plain RuntimeExceptions are covered too. `private[orca]`: user code never
    * participates.
    *
    * The contract assumes a freshly-constructed throwable per failure (as every
    * failure site in orca produces): identity marking would suppress a
    * semantically-new failure that happened to reuse a cached/singleton
    * exception instance — nothing in orca does that today.
    */
  private[orca] def markErrorReported(e: Throwable): Unit
  private[orca] def errorAlreadyReported(e: Throwable): Boolean

  /** Emit-once helper over the two primitives: runs `emit` and marks `e` only
    * when `e` hasn't been reported yet. The runtime's three report sites all
    * use this shape.
    */
  private[orca] final def reportOnce(e: Throwable)(emit: => Unit): Unit =
    if !errorAlreadyReported(e) then
      emit
      markErrorReported(e)

  /** Resolve the per-backend agent named by `tag` — the single definition
    * session rehydration (`FlowLifecycle.targetAgent`) resolves a persisted
    * record's backend tag against, so a renamed or added [[BackendTag]] case is
    * one match to update, not one per call site. Default implementation
    * dispatches to the five accessors above (only the matched one is touched,
    * so overriding is never required for laziness); a production `FlowContext`
    * may still override it to read a precomputed structure instead — see
    * `DefaultFlowContext.agentFor`. `private[orca]`: user code never needs it,
    * `agent`/`claude`/`codex`/… cover every use it has.
    */
  private[orca] def agentFor(tag: BackendTag): Agent[?] = tag match
    case BackendTag.ClaudeCode => claude
    case BackendTag.Codex      => codex
    case BackendTag.Opencode   => opencode
    case BackendTag.Pi         => pi
    case BackendTag.Gemini     => gemini
