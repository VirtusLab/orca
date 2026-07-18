package orca

import language.experimental.captureChecking

import scala.annotation.implicitNotFound

/** In-stage workspace-mutation token: the EXCLUSIVE half of the capability
  * split (ADR 0018 §6). Gates the Scala-side index-like writes — git/`gh`
  * writes, `fs.write`, progress-store writes — that must be sequenced by the
  * flow thread and must NOT cross a `fork` boundary. Minted and supplied by the
  * `stage` implementation like [[InStage]]; user code receives it `using` but
  * cannot fabricate one (private constructor).
  *
  * Extends `caps.ExclusiveCapability` (where [[InStage]] is a
  * `SharedCapability`), so separation checking forbids two concurrent closures
  * from both capturing it — the type-level encoding of "these mutations are
  * flow-thread-only". `ExclusiveCapability` is `@experimental` on 3.8.4, so
  * this file carries `import language.experimental.captureChecking`; that
  * taints only this compilation unit, not consumers (see ADR 0018 §6).
  *
  * Carries no state; it is a real class only so capture checking has a
  * reference to track.
  */
@implicitNotFound(
  "git/file/GitHub writes and progress-log writes must be made inside a `stage(...)` body — and, unlike LLM calls, must NOT be captured into a `fork`. Move this write into a stage (not a fork within one). If this is a helper meant to run inside a stage, declare it `(using WorkspaceWrite)` so its caller's token flows through."
)
final class WorkspaceWrite private () extends caps.ExclusiveCapability

object WorkspaceWrite:
  /** Mint a fresh [[WorkspaceWrite]] token. Called only by
    * `orca.RuntimeInStage` (the runtime's single named door) and test code;
    * library code must never call this directly.
    */
  private[orca] def unsafe: WorkspaceWrite = new WorkspaceWrite()
