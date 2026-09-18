package orca

import scala.annotation.implicitNotFound
import scala.util.NotGiven

/** Compile-time evidence that no [[InStage]] capability is in scope — i.e. the
  * call site is lexically outside a `stage(...)` body.
  *
  * Required by the PR helpers ([[orca.pr.openPrFromBranch]],
  * [[orca.pr.openPrIfGitHub]]): each runs stages of its own, and opening the PR
  * is a top-level step of a flow rather than part of one.
  *
  * Lexical only: it says nothing about the call stack. A helper declared with
  * just `(using FlowControl)` summons this evidence at its own definition site
  * and compiles, however deep inside a stage its callers sit; neither door
  * taking it checks at runtime.
  */
@implicitNotFound(
  "openPrFromBranch(...) and openPrIfGitHub(...) must be called outside a " +
    "stage, at the flow-body top level: each runs stages of its own."
)
final class OutsideStage private ()
object OutsideStage:
  given (using NotGiven[InStage]): OutsideStage = new OutsideStage
