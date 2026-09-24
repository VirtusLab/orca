package orca

import language.experimental.captureChecking

import orca.agents.SessionKey
import orca.gitref.CommitHash
import orca.progress.ProgressStore
import orca.sessions.SessionStore

import scala.annotation.implicitNotFound

/** Marker capability: the holder is permitted to start a new stage. Carries the
  * run-state `stage` needs — the progress store and a stack of per-stage
  * occurrence counters yielding each stage a hierarchical, path-structured id
  * (ADR 0018 §2.1).
  *
  * Unrelated to [[FlowContext]]: `flow` provides both as separate givens, so a
  * fork can capture the `FlowContext` alone. `stage`, and so any helper that
  * starts stages, requires `(using FlowContext, FlowControl)`.
  *
  * Thread-affine: one `FlowControl` exists per top-level `flow(...)` invocation
  * and must not be shared across threads (ADR 0018 §2.2). Extending
  * `caps.ExclusiveCapability` encodes that affinity: separation checking
  * forbids two concurrent closures from both capturing this exclusive
  * capability, so a `fork` cannot smuggle the authority to start a stage onto
  * another thread. (That marker is `@experimental` on 3.9.0, hence this file's
  * `captureChecking` import; the taint stays local to this compilation unit —
  * see ADR 0018 §6.) At runtime, [[StageFrames]]'s owner-thread assert enforces
  * it for [[withStage]] and [[claimSessionKey]].
  *
  * Not sealed: its implementation (`DefaultFlowControl`) lives in the `runner`
  * module, which depends on `flow`, not the reverse. An accepted guard-rail —
  * the open trait is not part of the public extension surface.
  *
  * The frame-stack mechanism and its invariants are documented on
  * [[StageFrames]], the mixin shared by every implementation.
  */
@implicitNotFound(
  "`stage(...)`, `agent.session(...)`, and `session.run(...)` on a FlowSession can only be called inside a `flow(...)` body — and not inside a `fork` (forks can read and emit, but can't start stages). If this is a helper that starts stages, declare it `(using FlowContext, FlowControl)` so its caller supplies both."
)
trait FlowControl extends caps.ExclusiveCapability:
  /** The store backing this run's progress log — the committed, branch-carried
    * half of a run's state.
    */
  def progressStore: ProgressStore

  /** The store backing this run's durable session records — the machine-local
    * half, in `.orca/cache/` (see [[orca.sessions.SessionStore]]).
    */
  def sessionStore: SessionStore

  /** Run `f` with a stage named `name` open, passing its path — see
    * [[StageFrames.withStage]].
    */
  private[orca] def withStage[R](name: String, baseCommit: Option[CommitHash])(
      f: StagePath.Stage => R
  ): R

  /** Throw unless no stage is open — see [[StageFrames.assertAtFlowBody]]. */
  private[orca] def assertAtFlowBody(what: String): Unit

  /** The commit the innermost open stage started from — the baseline for the
    * change set that stage has produced, whether or not it has since been
    * committed. `None` when no such commit was recorded (ADR 0018 §2.1).
    */
  private[orca] def stageBaseCommit: Option[CommitHash]

  /** The commit the RUN started from — HEAD when lifecycle setup bound the
    * branch, before any stage committed — the baseline for everything the run
    * has produced, as [[stageBaseCommit]] is for one stage. Recorded in the
    * progress header, so a resumed run reports the first attempt's commit, not
    * this attempt's.
    *
    * `None` when the header didn't record it, recorded something that isn't a
    * hash, or recorded one this repository can no longer diff against (pruned,
    * or rebased out of HEAD's history): a whole-run review then has no base and
    * says so rather than reviewing the wrong range.
    */
  private[orca] def startingCommit: Option[CommitHash]

  /** Key a session named `name` to the stage currently open and claim it,
    * throwing if `agent.session(...)` already minted that name there — see
    * [[StageFrames.claimSessionKey]].
    */
  private[orca] def claimSessionKey(name: String): SessionKey

  /** Claim the next turn against the conversation held under `sessionId` — see
    * [[StageFrames.claimTurn]].
    */
  private[orca] def claimTurn(sessionId: String): SessionTurn
