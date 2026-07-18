package orca

import language.experimental.captureChecking
import language.experimental.separationChecking

import ox.flow.Flow

/** The capture-checked concurrency funnel: orca's internal parallel fan-outs go
  * through here rather than calling Ox's combinators directly, so separation
  * checking can enforce the [[InStage]]/[[WorkspaceWrite]] capability split at
  * the fork boundary (ADR 0018 §6).
  *
  * Separation checking rejects an exclusive capability the moment it is widened
  * away. For a fan-out that is the thunk list's impure `() => T` element type: a
  * closure capturing an exclusive `WorkspaceWrite`/`FlowControl` fails with a
  * separation failure at the call site, while a shared `InStage` capture (a
  * `caps.SharedCapability`, separation-exempt) compiles — reviewers reach a
  * gated LLM `run` from inside their fork. The check is armed by the impure
  * element type at the call site, not by this wrapper's signature; the funnel's
  * job is to keep fan-out call sites in that checked shape, and it is the pinned
  * pattern the negative-compile suite compiles fixtures against.
  *
  * Raw Ox stays the unchecked escape hatch until Ox itself is capture-checked.
  * These wrappers are a bridge for deletion: once `ox.fork`/`Flow` carry capture
  * annotations, the rejection happens at the Ox call directly. Enforcement is
  * per compilation unit — a call site is only checked if its own file carries
  * the two language imports above.
  *
  * The wrappers delegate 1:1 to Ox with no behavioral additions.
  */
private[orca] object CheckedPar:
  /** Runs `thunks` concurrently (up to `parallelism` at a time, results
    * unordered), invoking `onResult` on each result as it completes on the
    * collecting thread, and returns every result as a list.
    *
    * The capture-set parameter `C^` is not interchangeable with plain impure
    * thunks (`Seq[() => T]`): that signature does not compile, since applying the
    * elements leaks the reach capability `thunks*` into the method's capture
    * scope. `C^` names the elements' captures so the body type-checks, while the
    * call site keeps the enforcement role (see the object doc). `onResult` is a
    * plain impure closure, unconstrained by `C`.
    */
  def mapParUnordered[T, C^](
      parallelism: Int
  )(thunks: Seq[() ->{C} T])(onResult: T => Unit): List[T] =
    Flow
      .fromIterable(thunks)
      .mapParUnordered(parallelism)(_.apply())
      .tap(onResult)
      .runToList()
