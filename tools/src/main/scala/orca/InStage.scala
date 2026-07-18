package orca

import scala.annotation.implicitNotFound

/** In-stage LLM-call token, minted and supplied by the `stage` implementation;
  * user code receives it `using` but cannot fabricate one (private
  * constructor). Gates stage-bound LLM runs so they can't run outside a stage
  * (ADR 0018 §2.2, §6).
  *
  * The SHARED half of the capability split (ADR 0018 §6): extends
  * `caps.SharedCapability`, so it may be captured freely into a `fork` (the
  * reviewer fan-out's shared `InStage` capture is load-bearing) and is exempt
  * from separation rules. The EXCLUSIVE half — [[WorkspaceWrite]] — gates
  * mutations that must NOT cross a fork boundary. `caps.SharedCapability` is
  * non-experimental on 3.8.4, so — unlike [[WorkspaceWrite]] — this file needs
  * no `captureChecking` language import.
  *
  * Carries no state; it is a real class only so capture checking has a
  * reference to track.
  */
@implicitNotFound(
  "LLM runs must be made inside a `stage(...)` body, which commits and checkpoints them. Move this call into a stage. If this is a helper meant to run inside a stage, declare it `(using InStage)` so its caller's token flows through."
)
final class InStage private () extends caps.SharedCapability

object InStage:
  /** Mint a fresh [[InStage]] token. Called only by `orca.RuntimeInStage` (the
    * runtime's single named door) and test code; library code must never call
    * this directly.
    */
  private[orca] def unsafe: InStage = new InStage()
