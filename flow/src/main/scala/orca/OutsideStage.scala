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
  * Lexical only: a helper taking just `(using FlowControl)` that is invoked
  * inside a stage still compiles, so a door taking this evidence needs a
  * runtime in-stage check of its own as the backstop.
  */
@implicitNotFound(
  "openPrFromBranch(...) and openPrIfGitHub(...) must be called outside a " +
    "stage, at the flow-body top level: each runs stages of its own."
)
final class OutsideStage private ()
object OutsideStage:
  given (using NotGiven[InStage]): OutsideStage = new OutsideStage
