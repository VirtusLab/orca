package orca

import language.experimental.captureChecking

import scala.annotation.implicitNotFound

/** In-stage workspace-mutation token: the EXCLUSIVE half of the capability
  * split (ADR 0018 §6). Gates the Scala-side index-like writes — git/`gh`
  * writes, `fs.write`, progress-store writes — that must be sequenced by the
  * flow thread and must NOT cross a `fork` boundary. Minted and supplied by the
  * `stage` implementation (package `orca`) exactly like [[InStage]]; user code
  * and tool wrappers receive it as a `using` parameter but can never fabricate
  * one — the `private` constructor and `private[orca]` mint ensure that.
  *
  * Where [[InStage]] extends `caps.SharedCapability` (freely capturable into
  * forks, separation-exempt), `WorkspaceWrite` extends
  * `caps.ExclusiveCapability`, so once capture checking is adopted separation
  * checking's hidden-set rules forbid two concurrent closures from both
  * capturing it — the type-level encoding of "these mutations are
  * flow-thread-only". `caps.ExclusiveCapability` is `@experimental` on 3.8.4,
  * so this file carries `import language.experimental.captureChecking`; that
  * taints only this compilation unit, not consumers (verified — see ADR 0018
  * §6).
  *
  * The value carries no state — it is evidence only; nothing reads anything off
  * it. Making it a real class is purely so capture checking has a reference to
  * track.
  *
  * `@implicitNotFound` keeps the missing-capability error user-facing: a flow
  * author never needs to know what `WorkspaceWrite` is, only that these writes
  * belong inside a `stage(...)` and not inside a `fork`.
  *
  * `private[orca]` still lets any code in the `orca` package — across modules —
  * call `unsafe`; an accepted guard-rail per ADR 0018 §5, with the convention
  * that only the `stage` runtime does so.
  */
@implicitNotFound(
  "git/file/GitHub writes and progress-log writes must be made inside a `stage(...)` body — and, unlike LLM calls, must NOT be captured into a `fork`. Move this write into a stage (not a fork within one). If this is a helper meant to run inside a stage, declare it `(using WorkspaceWrite)` so its caller's token flows through."
)
final class WorkspaceWrite private () extends caps.ExclusiveCapability

object WorkspaceWrite:
  /** Mint a fresh [[WorkspaceWrite]] token. Called only by
    * `orca.RuntimeInStage` (the runtime's single named door — see it for the
    * whitelist) and test code; library code must never call this directly.
    */
  private[orca] def unsafe: WorkspaceWrite = new WorkspaceWrite()
