package orca

import language.experimental.captureChecking

import orca.progress.ProgressStore

import scala.annotation.implicitNotFound

/** Marker capability: the holder is permitted to start a new stage.
  *
  * `FlowControl` is a subtype of [[FlowContext]] so that any code requiring
  * only a `FlowContext` can be given a `FlowControl` without an explicit cast.
  * The reverse is not true — a plain `FlowContext` cannot be widened to
  * `FlowControl` — which lets `flow` hand forks only the narrow type,
  * preventing them from starting nested stages.
  *
  * Thread-affine: one `FlowControl` exists per top-level `flow(...)` invocation
  * and must not be shared across threads (ADR 0018 §2.2). Extending
  * `caps.ExclusiveCapability` is the capture-checking encoding of that
  * affinity: once CC is adopted, separation checking forbids two concurrent
  * closures from both capturing this exclusive capability, so a `fork` cannot
  * smuggle the authority to start a stage onto another thread. (That marker is
  * `@experimental` on 3.8.4, hence this file's `captureChecking` language
  * import; the taint stays local to this compilation unit — see ADR 0018 §6.)
  *
  * Not sealed: its implementation (`DefaultFlowContext`) lives in the `runner`
  * module, which depends on `flow`, not the reverse. This is an accepted
  * guard-rail — the open trait is not part of the public extension surface.
  *
  * Carries the run-state the `stage` primitive needs: the progress store to
  * read/append against and a stack of per-stage occurrence counters that gives
  * each stage a hierarchical, path-structured id (ADR 0018 §2.1). `stage`
  * requires `(using FlowControl)`; `flow` supplies it.
  *
  * '''Stage-identity protocol (enter/lookup/exit).''' `stage` computes a
  * stage's id once, before deciding skip-vs-run, by calling [[enterStage]]
  * against the current (parent) frame — this both bumps the parent's occurrence
  * counter for the name (so the slot is consumed whether or not the body runs,
  * keeping later same-named siblings stable) and opens a child frame under
  * which nested `stage(...)` calls scope their own counters. The returned path
  * id drives the resume lookup; on a hit the body is skipped and [[exitStage]]
  * pops the frame immediately, so a skipped parent's nested stages are
  * structurally unreachable — the body never runs, so their `enterStage` never
  * fires and no counter desyncs on resume. On a miss the body runs inside the
  * frame and [[exitStage]] pops it in a `finally`.
  *
  * Thread-affine: `enterStage`/`exitStage` (and the occurrence counters they
  * drive) are covered by the same single-thread affinity contract as the rest
  * of `FlowControl` (see below); Epic 7.1's runtime owner-thread assert must
  * cover them alongside [[nextSessionOccurrence]].
  */
@implicitNotFound(
  "`stage(...)`, `agent.session(...)`, and `session.run(...)` on a FlowSession can only be called inside a `flow(...)` body — and not inside a `fork` (forks can read and emit, but can't start stages). If this is a helper that starts stages, declare it `(using FlowControl)` so its caller supplies it."
)
trait FlowControl extends FlowContext, caps.ExclusiveCapability:
  /** The store backing this run's progress log. */
  def progressStore: ProgressStore

  /** Open a stage named `name`: bump the current frame's occurrence counter for
    * the name, push a child frame, and return the stage's full path id (e.g.
    * `outer#0/inner#0`, or `name#0` at the flow-body top level). Called once by
    * `stage` before the resume lookup; must be balanced by [[exitStage]]. The
    * path segment format is `name#occurrence`, separated by `/`, and is stable
    * against inserting/removing *other* stages between runs.
    */
  def enterStage(name: String): String

  /** Pop the frame opened by the matching [[enterStage]]. */
  def exitStage(): Unit

  /** Whether execution is currently inside a stage body (any stage frame open).
    * Gates `agent.session(...)` to the flow-body top level.
    */
  def inStage: Boolean

  /** Next occurrence index for a session `name` in this run: 0 for the first
    * `agent.session(name, ...)`, 1 for the second, and so on. Keyed per-name
    * and independent of the stage frames — `agent.session(...)` is required to
    * be called outside any stage, so it always mints against the root scope.
    */
  def nextSessionOccurrence(name: String): Int
