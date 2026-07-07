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
  * read/append against and a per-run occurrence counter that disambiguates
  * same-named stages (ADR 0018 §2.1). `stage` requires `(using FlowControl)`;
  * `flow` supplies it.
  */
@implicitNotFound(
  "`stage(...)`, `agent.session(...)`, and `agent.runSeeded(...)` can only be called inside a `flow(...)` body — and not inside a `fork` (forks can read and emit, but can't start stages). If this is a helper that starts stages, declare it `(using FlowControl)` so its caller supplies it."
)
trait FlowControl extends FlowContext, caps.ExclusiveCapability:
  /** The store backing this run's progress log. */
  def progressStore: ProgressStore

  /** Next occurrence index for a stage `name` in this run: 0 for the first
    * `stage(name)`, 1 for the second, and so on. Stages run sequentially, so
    * this is plain per-run bookkeeping. Used to build a stage id (`name#index`)
    * that is stable against inserting/removing *other* stages between runs.
    */
  def nextOccurrence(stageName: String): Int

  /** Next occurrence index for a session `name` in this run: 0 for the first
    * `agent.session(name, ...)`, 1 for the second, and so on. Keyed per-name,
    * mirroring [[nextOccurrence]] for stages, and independent of the stage
    * counter so sessions can be obtained outside stages without perturbing
    * stage ids.
    */
  def nextSessionOccurrence(name: String): Int
