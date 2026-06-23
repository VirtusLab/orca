package orca

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
  * Note: `private[orca]` is the narrowest package-qualified scope available in
  * Scala 3. Modules are not packages, so any code in the `orca` package across
  * modules can technically call `unsafe` — this is an accepted guard-rail per
  * ADR 0018 §5. The convention is that only the `stage` runtime does so.
  */
opaque type InStage = Unit

object InStage:
  /** Mint a fresh [[InStage]] token. Called exclusively by the `stage` runtime
    * (package `orca`). Nothing outside the `orca` package can call this.
    */
  private[orca] def unsafe: InStage = ()
