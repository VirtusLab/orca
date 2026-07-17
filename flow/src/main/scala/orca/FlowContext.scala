package orca

import orca.events.OrcaEvent
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.agents.{Agent, BackendTag}

import scala.annotation.implicitNotFound

/** Ambient context a flow script operates in. Bundles every tool the top- level
  * accessors (`claude`, `codex`, `opencode`, `pi`, `gemini`, `git`, `gh`, `fs`)
  * resolve against, the user's positional prompt (`userPrompt`), and the event
  * sink (`emit`) that stage/fail/fixLoop and the library's internals publish
  * to.
  *
  * One is built per `flow(...)` invocation — flow scripts don't normally
  * instantiate `FlowContext` directly, just call the accessors inside a
  * `flow(args): ...` block, which supplies the given instance.
  *
  * The five per-backend accessors (`claude`, `codex`, …) come from
  * [[AgentSet]]. The three role accessors ([[planningAgent]] / [[codingAgent]]
  * / [[reviewAgent]], ADR 0020) are resolved from settings against that set
  * before the context exists, each landing on its own backend.
  */
@implicitNotFound(
  "the flow tools (`claude`/`codex`/`git`/`gh`/`fs`/…), `display`, and `fail` are only available inside a `flow(...)` body. Wrap this code in `flow(OrcaArgs(args), _.claude): ...`."
)
trait FlowContext extends AgentSet:
  /** Backend tag of the planning-role agent (ADR 0020): resolved from
    * `planningAgent = <harness>[:<model>]` in stack settings, default claude. A
    * type *member*, not a parameter, so `FlowContext` stays unparametrised;
    * pins [[planningAgent]]'s backend so sessions minted from it thread. See
    * [[CodeB]] for the helper-authoring caveat shared by all three role types.
    */
  type PlanB <: BackendTag

  /** Backend tag of the coding-role agent (ADR 0020): resolved from
    * `codingAgent = <harness>[:<model>]` in stack settings, default claude —
    * the run's primary backend. A type *member*, not a parameter, so
    * `FlowContext` stays unparametrised; pins [[codingAgent]]'s backend so
    * sessions minted from it thread.
    *
    * '''Helper authoring:''' the path-dependent `Agent[ctx.CodeB]` (the same
    * holds for `ctx.PlanB` / `ctx.ReviewB`) is convenient in a straight-line
    * `flow(...)` body, where every reference resolves against the same `using
    * FlowContext` in scope — but it stops working the moment you factor code
    * into a helper *function*. `ctx1.CodeB` and `ctx2.CodeB` from two different
    * `using FlowContext` parameters are different types to the compiler even
    * when they're the same backend at runtime, so a helper that takes
    * `Agent[ctx.CodeB]` in one parameter and tries to combine it with
    * `SessionId[ctx.CodeB]` from another can't unify them. Two ways out, both
    * used by the library's own helpers:
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
  type CodeB <: BackendTag

  /** Backend tag of the review-role agent (ADR 0020): resolved from
    * `reviewAgent = <harness>[:<model>]` in stack settings, default claude. A
    * type *member*, not a parameter, so `FlowContext` stays unparametrised;
    * pins [[reviewAgent]]'s backend so sessions minted from it thread. See
    * [[CodeB]] for the helper-authoring caveat shared by all three role types.
    */
  type ReviewB <: BackendTag

  /** The planning-role agent (ADR 0020): resolved from settings, default
    * claude. Scripts hand it to `Plan.*`.
    */
  def planningAgent: Agent[PlanB]

  /** The coding-role agent — the run's primary: implementer sessions, branch
    * naming, stack discovery, and default commit messages run here.
    */
  def codingAgent: Agent[CodeB]

  /** The review-role agent: `allReviewers(reviewAgent)`, the reviewer-picker
    * and the lint summariser default to its tiers.
    */
  def reviewAgent: Agent[ReviewB]

  def git: GitTool
  def gh: GitHubTool
  def fs: FsTool

  /** The working tree the flow runs against. */
  def workDir: os.Path

  /** Resolved stack settings (ADR 0019): resolved once during lifecycle setup —
    * override > `.orca/settings.properties` > auto-discovery — and frozen for
    * the run.
    */
  def stackSettings: StackSettings

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
