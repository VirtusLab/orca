package orca.runner

import orca.{FlowControl, StackSettings}
import orca.progress.ProgressStore
import orca.tools.{FsTool, GitHubTool, GitTool}
import orca.agents.{Agent, BackendTag}
import orca.events.{EventDispatcher, OrcaEvent}

import ox.discard

/** Production FlowContext wiring. Constructed by `runFlow` AFTER the three role
  * agents are resolved and lifecycle setup has run, so the role agents and
  * `stackSettings` are plain constructor facts. Ownership of the wired agents
  * transfers here at construction: from that point `close()` is the sole
  * disposal path (before it, `runFlow`'s ownership guard covers them).
  */
private[orca] class DefaultFlowContext[
    PB <: BackendTag,
    CB <: BackendTag,
    RB <: BackendTag
](
    val userPrompt: String,
    val workDir: os.Path,
    dispatcher: EventDispatcher,
    // The three role agents (ADR 0020), resolved by `runFlow` from settings
    // against the wired agent set (with per-role programmatic overrides on
    // top). Each is concretely typed via its own tag parameter — opened from
    // the runtime `Agent[?]` values with type-variable patterns — so the role
    // type members pin them and sessions thread.
    val planningAgent: Agent[PB],
    val codingAgent: Agent[CB],
    val reviewAgent: Agent[RB],
    wired: WiredAgents,
    val git: GitTool,
    val gh: GitHubTool,
    val fs: FsTool,
    val progressStore: ProgressStore,
    /** Resolved stack settings (ADR 0019): `FlowLifecycle.setup` resolves them
      * before the context is constructed, so they arrive frozen — the body (and
      * the loops it calls) sees one immutable value.
      */
    val stackSettings: StackSettings
) extends FlowControl,
      orca.StageFrames:

  // Each role's backend tag, pinned from its type parameter. Concrete here, so
  // the role accessors are concretely typed and sessions thread; abstract when
  // the context is seen as `FlowContext` in a body, where the path-dependent
  // `ctx.PlanB` / `ctx.CodeB` / `ctx.ReviewB` are still stable.
  type PlanB = PB
  type CodeB = CB
  type ReviewB = RB

  export wired.{claude, codex, opencode, pi, gemini}

  /** Tear down context-owned background resources by closing every wired agent
    * plus the three resolved role agents. Runs in the flow body's `finally`,
    * before the flow scope joins its forks (see
    * [[orca.backend.AgentBackend.close]]).
    *
    * The role agents are UNCONDITIONALLY appended to the fan-out rather than
    * filtered by [[WiredAgents.isWiredBackend]] first: a foreign role agent (a
    * programmatic override like `_ => myPrebuiltAgent`, built from a separate
    * `AgentWiring`/backend) is otherwise unreachable from the wired set and
    * would leak past flow end, and a role that DOES share a wired backend (the
    * common settings-resolved and `_.claude.opus` cases) just gets `close()`
    * called on it a second time — provably harmless, since every backend's
    * `close()` is idempotent (the shared `closedFlag` latches via a plain
    * `set`, opencode's teardown is CAS-guarded, and every other backend's
    * `close()` is a no-op). Skipping the check here trades a handful of
    * redundant `close()` calls for one less thing this method has to get right.
    */
  def close(): Unit =
    WiredAgents.closeBestEffort(
      wired.all ++ List(planningAgent, codingAgent, reviewAgent)
    )

  def emit(event: OrcaEvent): Unit = dispatcher.onEvent(event)

  // Written possibly from fork threads (`fail` inside a parallel block), read on
  // the stage thread during unwind — pure atomic state, per the concurrency
  // conventions. Identity comparison: the mark belongs to the object instance.
  private val reportedErrors =
    new java.util.concurrent.atomic.AtomicReference[List[Throwable]](Nil)
  private[orca] def markErrorReported(e: Throwable): Unit =
    reportedErrors.updateAndGet(e :: _).discard
  private[orca] def errorAlreadyReported(e: Throwable): Boolean =
    reportedErrors.get().exists(_ eq e)

  // Stage-identity bookkeeping (enterStage/exitStage/inStage and
  // nextSessionOccurrence) comes from the shared `StageFrames` mixin — the
  // single source of truth for the hierarchical frame-stack semantics, so the
  // test doubles cannot drift from production.
