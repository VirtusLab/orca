package orca

import scala.annotation.implicitNotFound

/** In-stage mutation token. Opaque so that only runtime code (the `stage`
  * implementation, which lives in package `orca`) can construct an instance.
  *
  * User code and tool wrappers receive an `InStage` as a `using` parameter but
  * can never fabricate one themselves — the `private[orca]` constructor ensures
  * this. This gates all stage-bound mutations so that they cannot accidentally
  * be called outside a running stage (ADR 0018 §2.2).
  *
  * The representation is `Unit`; only the opaque boundary matters here.
  *
  * `@implicitNotFound` turns the missing-capability compile error into a
  * user-facing instruction: a flow author never needs to know what `InStage`
  * is, only that side-effecting calls belong inside a `stage(...)`. Without it
  * the compiler reports a cryptic "No given instance of type orca.InStage" that
  * names an internal type. (Scala 3 honours the annotation on an opaque type
  * alias — verified on 3.8.3.)
  *
  * Note: `private[orca]` is the narrowest package-qualified scope available in
  * Scala 3. Modules are not packages, so any code in the `orca` package across
  * modules can technically call `unsafe` — this is an accepted guard-rail per
  * ADR 0018 §5. The convention is that only the `stage` runtime does so.
  */
@implicitNotFound(
  "side-effecting calls (git/file/GitHub writes, LLM runs) must be made inside a `stage(...)` body, which commits and checkpoints them. Move this call into a stage."
)
opaque type InStage = Unit

object InStage:
  /** Mint a fresh [[InStage]] token. Called exclusively by the `stage` runtime
    * (package `orca`). Nothing outside the `orca` package can call this.
    */
  private[orca] def unsafe: InStage = ()
