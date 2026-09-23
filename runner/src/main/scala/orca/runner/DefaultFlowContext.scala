package orca.runner

import orca.{FlowControl, StackSettings}
import orca.gitref.CommitHash
import orca.progress.ProgressStore
import orca.sessions.SessionStore
import orca.review.ReviewerCatalog
import orca.tools.{FsTool, GitHubTool, GitTool}
import orca.agents.{Agent, BackendTag}
import orca.events.{OrcaEvent, OrcaListener}

import ox.discard

/** Production FlowContext wiring. Constructed by `runFlow` AFTER the three role
  * agents are resolved and lifecycle setup has run, so the role agents and
  * `stackSettings` are plain constructor facts. Does not own the agents:
  * `runFlow` closes them when the run ends.
  */
private[orca] class DefaultFlowContext[
    PB <: BackendTag,
    CB <: BackendTag,
    RB <: BackendTag
](
    val userPrompt: String,
    val workDir: os.Path,
    dispatcher: OrcaListener,
    // The three role agents (ADR 0020), resolved by `runFlow`. Each is
    // concretely typed via its own tag parameter so the role type members pin
    // them and sessions thread.
    val planningAgent: Agent[PB],
    val codingAgent: Agent[CB],
    val reviewAgent: Agent[RB],
    wired: WiredAgents,
    val git: GitTool,
    val gh: GitHubTool,
    val fs: FsTool,
    val progressStore: ProgressStore,
    val sessionStore: SessionStore,
    /** Resolved stack settings (ADR 0019): `FlowLifecycle.setup` resolves them
      * before the context is constructed, so they arrive frozen — the body (and
      * the loops it calls) sees one immutable value.
      */
    val stackSettings: StackSettings,
    /** The reviewer definitions this run works from (shipped set + discovered
      * tiers): resolved by `runFlow` before the context exists, so it arrives
      * frozen like `stackSettings`.
      */
    val reviewerCatalog: ReviewerCatalog,
    /** The commit the run started from (see
      * [[orca.FlowControl.startingCommit]]) — like `stackSettings`, resolved by
      * `FlowLifecycle.setup` before the context exists, so it arrives frozen.
      */
    private[orca] val startingCommit: Option[CommitHash]
) extends FlowControl,
      orca.StageFrames:

  // Each role's backend tag, pinned from its type parameter — concrete here so
  // the role accessors are concretely typed and sessions thread.
  type PlanB = PB
  type CodeB = CB
  type ReviewB = RB

  export wired.{claude, codex, opencode, pi, gemini}

  def emit(event: OrcaEvent): Unit = dispatcher.onEvent(event)

  // Written possibly from fork threads (`fail` inside a parallel block), read on
  // the stage thread during unwind. Identity comparison: the mark belongs to the
  // object instance.
  private val reportedErrors =
    new java.util.concurrent.atomic.AtomicReference[List[Throwable]](Nil)
  private[orca] def markErrorReported(e: Throwable): Unit =
    reportedErrors.updateAndGet(e :: _).discard
  private[orca] def errorAlreadyReported(e: Throwable): Boolean =
    reportedErrors.get().exists(_ eq e)

  // Stage-identity bookkeeping (withStage, claimSessionKey) and the
  // per-run turn claim come from the shared `StageFrames` mixin, so test
  // doubles cannot drift from production.
