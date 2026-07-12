package orca

import language.experimental.captureChecking
import language.experimental.separationChecking

import ox.flow.Flow

/** The capture-checked concurrency funnel: orca's internal parallel fan-outs go
  * through here rather than calling Ox's combinators directly, so separation
  * checking can enforce the [[InStage]]/[[WorkspaceWrite]] capability split at
  * the fork boundary (ADR 0018 §6).
  *
  * Why this exists: separation checking rejects an exclusive capability the
  * moment it is widened away ("hidden") — for a fan-out, that is the thunk
  * list's impure element type. A `tasks: Seq[() => T]` whose closures capture
  * an exclusive `WorkspaceWrite`/`FlowControl` (an index-like write racing
  * across forks — ADR 0018 §6) fails with "Separation failure" right at that
  * definition, while the load-bearing shared `InStage` capture — reviewers
  * must reach a gated LLM `run` from inside their fork — compiles, because
  * `caps.SharedCapability` is separation-exempt. The wrapper's signature alone
  * does NOT reject: a thunk list with a precise inferred capture set (e.g.
  * `Seq[() ->{tok} T]` for an exclusive `tok`) passes through unflagged — the
  * impure `() => T` element type at the call site is what arms the check, and
  * it fires identically whether the list is then handed to this wrapper or to
  * raw Ox (all four combinations verified by compiling fixture variants). The
  * funnel's job is to keep fan-out call sites in the checked shape: its
  * capture-set-polymorphic signature forces a heterogeneous thunk list to be
  * widened to a single element type — in practice the impure `() => T` (see
  * the CC-forced type application in `orca.review.ReviewLoop`) — and it is the
  * pinned pattern the negative-compile suite compiles fixtures against.
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
    * The capture-set parameter `C^` is not interchangeable with taking plain
    * impure thunks (`Seq[() => T]`, i.e. `() ->{caps.cap} T`): with that
    * signature this method does not compile — applying the elements leaks the
    * local reach capability `thunks*` into the method's capture scope, and the
    * compiler error suggests exactly this capset-variable abstraction. `C^`
    * gives the elements' captures a name this method's body can be checked
    * against, while the call site keeps the enforcement role: widening thunks
    * that capture an exclusive `caps.ExclusiveCapability` ([[WorkspaceWrite]],
    * [[FlowControl]]) to the impure `() => T` element type is rejected there
    * with a separation failure, while a shared [[InStage]] capture is admitted
    * (see the object doc for what happens without that widening). `onResult`
    * is a plain impure closure (it captures the caller's `FlowContext` to emit
    * progress), unconstrained by `C`.
    */
  def mapParUnordered[T, C^](
      parallelism: Int
  )(thunks: Seq[() ->{C} T])(onResult: T => Unit): List[T] =
    Flow
      .fromIterable(thunks)
      .mapParUnordered(parallelism)(_.apply())
      .tap(onResult)
      .runToList()
