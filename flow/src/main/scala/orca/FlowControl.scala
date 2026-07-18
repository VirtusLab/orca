package orca

import language.experimental.captureChecking

import orca.progress.ProgressStore

import scala.annotation.implicitNotFound

/** Marker capability: the holder is permitted to start a new stage. Carries the
  * run-state `stage` needs — the progress store and a stack of per-stage
  * occurrence counters yielding each stage a hierarchical, path-structured id
  * (ADR 0018 §2.1). `stage` requires `(using FlowControl)`; `flow` supplies it.
  *
  * A subtype of [[FlowContext]] but not the reverse, so `flow` can hand forks
  * only the narrow `FlowContext`, preventing them from starting nested stages.
  *
  * Thread-affine: one `FlowControl` exists per top-level `flow(...)` invocation
  * and must not be shared across threads (ADR 0018 §2.2). Extending
  * `caps.ExclusiveCapability` encodes that affinity: separation checking
  * forbids two concurrent closures from both capturing this exclusive
  * capability, so a `fork` cannot smuggle the authority to start a stage onto
  * another thread. (That marker is `@experimental` on 3.8.4, hence this file's
  * `captureChecking` import; the taint stays local to this compilation unit —
  * see ADR 0018 §6.) At runtime, [[StageFrames]]'s owner-thread assert enforces
  * it for `enterStage`/`exitStage` and [[nextSessionOccurrence]].
  *
  * Not sealed: its implementation (`DefaultFlowContext`) lives in the `runner`
  * module, which depends on `flow`, not the reverse. An accepted guard-rail —
  * the open trait is not part of the public extension surface.
  *
  * The frame-stack mechanism and its invariants are documented on
  * [[StageFrames]], the mixin shared by every implementation.
  */
@implicitNotFound(
  "`stage(...)`, `agent.session(...)`, and `session.run(...)` on a FlowSession can only be called inside a `flow(...)` body — and not inside a `fork` (forks can read and emit, but can't start stages). If this is a helper that starts stages, declare it `(using FlowControl)` so its caller supplies it."
)
trait FlowControl extends FlowContext, caps.ExclusiveCapability:
  /** The store backing this run's progress log. */
  def progressStore: ProgressStore

  /** Open a stage named `name` and return its full path id (e.g.
    * `outer#0/inner#0`). Called once by `stage` before the resume lookup; must
    * be balanced by [[exitStage]]. See [[StageFrames]] for the protocol.
    */
  def enterStage(name: String): String

  /** Pop the frame opened by the matching [[enterStage]]. */
  def exitStage(): Unit

  /** Whether execution is currently inside a stage body (any stage frame open).
    * Gates `agent.session(...)` to the flow-body top level.
    */
  def inStage: Boolean

  /** Throw unless the caller is on this control's owner thread — implemented by
    * [[StageFrames]]; called by the durable run doors so `session.run` from a
    * fork fails immediately instead of racing the progress log.
    */
  private[orca] def assertOwnerThread(what: String): Unit

  /** Next occurrence index for a session `name` in this run: 0 for the first
    * `agent.session(name, ...)`, 1 for the second, and so on. Keyed per-name
    * and independent of the stage frames — `agent.session(...)` is required to
    * be called outside any stage, so it always mints against the root scope.
    */
  def nextSessionOccurrence(name: String): Int
