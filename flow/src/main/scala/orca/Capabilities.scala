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
  * Today it carries no extra members; the `stage` primitive (task B2) will
  * require `(using FlowControl)` and `flow` will supply it.
  */
trait FlowControl extends FlowContext

/** In-stage mutation token. Opaque so that only runtime code (the `stage`
  * implementation, which lives in package `orca`) can construct an instance.
  *
  * User code and tool wrappers receive an `InStage` as a `using` parameter but
  * can never fabricate one themselves — the `private[orca]` constructor ensures
  * this. This gates all stage-bound mutations so that they cannot accidentally
  * be called outside a running stage (ADR 0018 §2.2).
  *
  * The representation is `Unit`; only the opaque boundary matters here.
  */
opaque type InStage = Unit

object InStage:
  /** Mint a fresh [[InStage]] token. Called exclusively by the `stage` runtime
    * (package `orca`). Nothing outside the `orca` package can call this.
    */
  private[orca] def unsafe: InStage = ()
