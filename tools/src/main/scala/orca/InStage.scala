package orca

import scala.annotation.implicitNotFound

// TODO: let's not explain how @implicitNotFound or selaed traits work - we assume that the reader/write know how Scala 3 works. Similarly in other comments.
/** In-stage LLM-call token. A capability the `stage` implementation (which
  * lives in package `orca`) mints and supplies to its body; user code and tool
  * wrappers receive it as a `using` parameter but can never fabricate one — the
  * `private` constructor and `private[orca]` mint ensure that. This gates
  * stage-bound LLM runs so they cannot accidentally be called outside a running
  * stage (ADR 0018 §2.2, §6).
  *
  * `InStage` is the SHARED half of the capability split (ADR 0018 §6): it
  * extends `caps.SharedCapability`, so once capture checking is adopted it may
  * be freely captured into a `fork` (the reviewer fan-out's shared `InStage`
  * capture is load-bearing) and is exempt from separation rules. The EXCLUSIVE
  * half — [[WorkspaceWrite]] — gates index-like mutations that must NOT cross a
  * fork boundary. `caps.SharedCapability` is non-experimental on 3.8.4, so this
  * file needs no language import; `caps.Capability` is sealed, so we extend the
  * sub-marker, never the root.
  *
  * The value carries no state — it is evidence only; nothing reads anything off
  * it. Making it a real class (rather than an opaque `Unit`) is purely so that
  * capture checking has a reference to track (a `Unit` value is pure).
  *
  * `@implicitNotFound` turns the missing-capability compile error into a
  * user-facing instruction: a flow author never needs to know what `InStage`
  * is, only that LLM calls belong inside a `stage(...)`. Without it the
  * compiler reports a cryptic "No given instance of type orca.InStage" that
  * names an internal type. (Scala 3 honours the annotation on a class —
  * verified on 3.8.4.)
  *
  * Note: `private[orca]` is the narrowest package-qualified scope available in
  * Scala 3. Modules are not packages, so any code in the `orca` package across
  * modules can technically call `unsafe` — this is an accepted guard-rail per
  * ADR 0018 §5. The convention is that only the `stage` runtime does so.
  */
@implicitNotFound(
  "LLM runs must be made inside a `stage(...)` body, which commits and checkpoints them. Move this call into a stage. If this is a helper meant to run inside a stage, declare it `(using InStage)` so its caller's token flows through."
)
final class InStage private () extends caps.SharedCapability

object InStage:
  /** Mint a fresh [[InStage]] token. Called only by `orca.RuntimeInStage` (the
    * runtime's single named door — see it for the whitelist) and test code;
    * library code must never call this directly.
    */
  private[orca] def unsafe: InStage = new InStage()
