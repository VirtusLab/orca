package orca.runner

import orca.{FlowControl, StackSettings}
import orca.progress.ProgressStore
import orca.tools.{FsTool, GitHubTool, GitTool}
import orca.agents.{Agent, BackendTag}
import orca.events.{EventDispatcher, OrcaEvent}

import ox.discard

/** Production FlowContext wiring. Constructed by `runFlow` AFTER the lead agent
  * is resolved and lifecycle setup has run, so `agent` and `stackSettings` are
  * plain constructor facts. Ownership of the wired agents transfers here at
  * construction: from that point `close()` is the sole disposal path (before
  * it, `runFlow`'s ownership guard covers them).
  */
private[orca] class DefaultFlowContext[B <: BackendTag](
    val userPrompt: String,
    val workDir: os.Path,
    dispatcher: EventDispatcher,
    // The leading agent, resolved by `runFlow` against the wired agent set
    // (the only way to name an agent in a selector is an accessor on
    // `AgentSet` — `_.claude`, `_.codex`, …). Concretely typed via `B` (which
    // `flow` inferred from the selector) so `LeadB` pins it and sessions
    // thread.
    val agent: Agent[B],
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

  // The leading agent's backend tag, pinned from the type parameter `B`.
  // Concrete here, so `agent` is concretely typed and sessions thread;
  // abstract when the context is seen as `FlowContext` in a body, where the
  // path-dependent `ctx.LeadB` is still stable.
  type LeadB = B

  // Every role is backed by the same lead agent; ADR 0020 specifies per-role
  // resolution. All three pin to `B`, and the accessors just forward to
  // `agent`.
  type PlanB = B
  type CodeB = B
  type ReviewB = B
  val planningAgent: Agent[B] = agent
  val codingAgent: Agent[B] = agent
  val reviewAgent: Agent[B] = agent

  export wired.{claude, codex, opencode, pi, gemini}

  /** Tear down context-owned background resources by closing every wired agent
    * plus the resolved lead. Runs in the flow body's `finally`, before the flow
    * scope joins its forks (see [[orca.backend.AgentBackend.close]]).
    *
    * The lead is UNCONDITIONALLY appended to the fan-out rather than filtered
    * by [[WiredAgents.isWiredBackend]] first: a foreign lead (a selector like
    * `_ => myPrebuiltAgent`, built from a separate `AgentWiring`/backend) is
    * otherwise unreachable from the wired set and would leak past flow end, and
    * a lead that DOES share a wired backend (the common `_.claude.opus`
    * pattern) just gets `close()` called on it a second time — provably
    * harmless, since every backend's `close()` is idempotent (the shared
    * `closedFlag` latches via a plain `set`, opencode's teardown is
    * CAS-guarded, and every other backend's `close()` is a no-op). Skipping the
    * check here trades a handful of redundant `close()` calls for one less
    * thing this method has to get right.
    */
  def close(): Unit = WiredAgents.closeBestEffort(wired.all :+ agent)

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
