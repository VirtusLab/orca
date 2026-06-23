package orca

/** Marker capability: the holder is permitted to start a new stage.
  *
  * `FlowControl` is a subtype of [[FlowContext]] so that any code requiring
  * only a `FlowContext` can be given a `FlowControl` without an explicit cast.
  * The reverse is not true — a plain `FlowContext` cannot be widened to
  * `FlowControl` — which lets `flow` hand forks only the narrow type,
  * preventing them from starting nested stages.
  *
  * Thread-affine: one `FlowControl` exists per top-level `flow(...)` invocation
  * and must not be shared across threads (ADR 0018 §2.2).
  *
  * Not sealed: its implementation (`DefaultFlowContext`) lives in the `runner`
  * module, which depends on `flow`, not the reverse. This is an accepted
  * guard-rail — the open trait is not part of the public extension surface.
  *
  * Today it carries no extra members; the `stage` primitive (task B2) will
  * require `(using FlowControl)` and `flow` will supply it.
  */
trait FlowControl extends FlowContext
