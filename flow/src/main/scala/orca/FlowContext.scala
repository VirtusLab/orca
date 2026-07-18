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
  "the flow tools (`claude`/`codex`/`git`/`gh`/`fs`/…), `display`, and `fail` are only available inside a `flow(...)` body. Wrap this code in `flow(OrcaArgs(args)): ...`."
)
trait FlowContext extends AgentSet:
  /** Backend tag of the planning-role agent (ADR 0020): resolved from
    * `planningAgent = harness[:model]` in settings, default claude. A type
    * member, not a parameter, so `FlowContext` stays unparametrised; pins
    * [[planningAgent]]'s backend so sessions minted from it thread. See
    * [[CodeB]] for the helper-authoring caveat shared by all three role types.
    */
  type PlanB <: BackendTag

  /** Backend tag of the coding-role agent (ADR 0020): resolved from
    * `codingAgent = harness[:model]` in settings, default claude — the run's
    * primary backend. A type member, not a parameter, so `FlowContext` stays
    * unparametrised; pins [[codingAgent]]'s backend so sessions minted from it
    * thread.
    *
    * '''Helper authoring:''' the path-dependent `Agent[ctx.CodeB]` (likewise
    * `ctx.PlanB` / `ctx.ReviewB`) is fine in a straight-line `flow(...)` body,
    * but a helper *function* should take an explicit `[B <: BackendTag]` type
    * parameter instead, so it works for whichever backend settings named and
    * stays callable wherever the session value is held. Two shapes the library
    * uses:
    *
    *   - Type the helper's parameters against `[B <: BackendTag]` — see
    *     [[orca.review.reviewAndFixLoop]]`(coderSession: FlowSession[B], ...)`,
    *     whose [[orca.FlowSession]] bundles the agent and its session so `B` is
    *     pinned once at the call site.
    *   - Bundle session and result as a single [[orca.plan.Sessioned]]`[B, A]`
    *     so callers pass one thing, not two that must agree on `B`. See
    *     `Plan.autonomous.*` / `Plan.interactive.*`.
    */
  type CodeB <: BackendTag

  /** Backend tag of the review-role agent (ADR 0020): resolved from
    * `reviewAgent = harness[:model]` in settings, default claude. A type
    * member, not a parameter, so `FlowContext` stays unparametrised; pins
    * [[reviewAgent]]'s backend so sessions minted from it thread. See [[CodeB]]
    * for the helper-authoring caveat shared by all three role types.
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
    * (`eq`), so it covers plain RuntimeExceptions too.
    *
    * Assumes a freshly-constructed throwable per failure (as every orca failure
    * site produces): identity marking would wrongly suppress a semantically-new
    * failure that reused a cached/singleton exception instance.
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
