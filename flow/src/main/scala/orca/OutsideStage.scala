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
  * Lexical only: a helper declared with just `(using FlowControl)` summons this
  * evidence at its own definition site and compiles, however deep inside a
  * stage its callers sit. Both doors therefore also check at run time
  * (`FlowControl.assertAtFlowBody`); this evidence turns the common case, a
  * call written in a stage body, into a compile error instead of a failure at
  * the end of a run.
  */
@implicitNotFound(
  "openPrFromBranch(...) and openPrIfGitHub(...) must be called outside a " +
    "stage, at the flow-body top level: each runs stages of its own."
)
final class OutsideStage private ()
object OutsideStage:
  given (using NotGiven[InStage]): OutsideStage = new OutsideStage
