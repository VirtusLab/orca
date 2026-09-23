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
  * flow-thread-only". `ExclusiveCapability` is `@experimental` on 3.9.0, so
  * this file carries `import language.experimental.captureChecking`; that
  * taints only this compilation unit, not consumers (see ADR 0018 §6).
  *
  * Separation checking covers only code compiled with it, so every gated write
  * also calls [[check]]: the token is bound to the thread that minted it, and a
  * write from any other thread throws.
  */
@implicitNotFound(
  "git/file/GitHub writes and progress-log writes must be made inside a `stage(...)` body — and, unlike LLM calls, must NOT be captured into a `fork`. Move this write into a stage (not a fork within one). If this is a helper meant to run inside a stage, declare it `(using WorkspaceWrite)` so its caller's token flows through."
)
final class WorkspaceWrite private (owner: Thread)
    extends caps.ExclusiveCapability:

  /** Throws unless called on the thread that minted this token. Called first by
    * every gated write, with `what` naming the write.
    */
  private[orca] def check(what: String): Unit =
    if Thread.currentThread() ne owner then
      throw new OrcaFlowException(
        s"$what called off the flow thread (inside a `fork`, `supervised` " +
          "body, `Par.mapUnordered` or `timeout`) — return the data from " +
          "there and write on the flow thread (ADR 0018 §6)"
      )

object WorkspaceWrite:
  /** Mint a fresh [[WorkspaceWrite]] token bound to the calling thread. Called
    * only by `orca.RuntimeInStage` (the runtime's single named door) and test
    * code; library code must never call this directly.
    */
  private[orca] def unsafe: WorkspaceWrite =
    new WorkspaceWrite(Thread.currentThread())
