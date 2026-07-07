package orca

import language.experimental.captureChecking
import language.experimental.separationChecking

import ox.flow.Flow

/** The capture-checked concurrency funnel: orca's internal parallel fan-outs go
  * through here rather than calling Ox's combinators directly, so separation
  * checking can enforce the [[InStage]]/[[WorkspaceWrite]] capability split at
  * the fork boundary (ADR 0018 §6).
  *
  * Why this exists: separation checking only fires through a
  * capture-checked-compiled combinator. Ox 1.0.x is not yet capture-checked, so
  * a fork opened via a raw `ox.flow.Flow.mapParUnordered` — or `ox.fork` — is
  * unchecked: a closure could capture an exclusive `WorkspaceWrite`/`FlowControl`
  * (an index-like write racing across forks — ADR 0018 §6) and the compiler
  * would stay silent. Routing through these thin, CC-compiled wrappers makes
  * that capture a compile error at the call site (verified: an exclusive capture
  * fails with "Separation failure", while the load-bearing shared `InStage`
  * capture — reviewers must reach a gated LLM `run` from inside their fork —
  * compiles, because `caps.SharedCapability` is separation-exempt).
  *
  * Raw Ox stays the unchecked escape hatch until Ox itself is capture-checked
  * (`Ox` on its roadmap). These wrappers are a bridge designed for deletion:
  * once `ox.fork`/`Flow` carry capture annotations, the fork-boundary rejection
  * happens at the Ox call directly and this funnel stops being load-bearing.
  * Enforcement is per compilation unit — a call site is only checked if its own
  * file carries the two language imports above.
  *
  * The wrappers delegate 1:1 to Ox with no behavioral additions; their value is
  * purely their compilation mode and their capture-annotated types.
  */
private[orca] object CheckedPar:
  /** Capture-checked delegate for the reviewer fan-out's
    * `Flow.fromIterable(thunks).mapParUnordered(parallelism)(_.apply()).tap(onResult).runToList()`
    * pipeline. Runs `thunks` concurrently (up to `parallelism` at a time,
    * results unordered), invoking `onResult` on each result as it completes on
    * the collecting thread, and returns every result as a list.
    *
    * The thunk capture set `C^` is abstracted into a capture-set parameter so
    * the elements' captured capabilities are tracked at the call site rather
    * than leaking into this method's scope: a `thunks` element that captures an
    * exclusive `caps.ExclusiveCapability` ([[WorkspaceWrite]], [[FlowControl]])
    * is rejected there with a separation failure, while a shared [[InStage]]
    * capture is admitted. `onResult` is a plain impure closure (it captures the
    * caller's `FlowContext` to emit progress), unconstrained by `C`.
    */
  def mapParUnordered[T, C^](
      parallelism: Int
  )(thunks: Seq[() ->{C} T])(onResult: T => Unit): List[T] =
    Flow
      .fromIterable(thunks)
      .mapParUnordered(parallelism)(_.apply())
      .tap(onResult)
      .runToList()
