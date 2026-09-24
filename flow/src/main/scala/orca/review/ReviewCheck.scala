package orca.review

import orca.{FlowContext, InStage}

/** A check written in Scala — a benchmark, an HTTP probe, a scripted assertion
  * — whose findings [[reviewAndFixLoop]] and [[reviewThenFix]] hand to the
  * fixer alongside the reviewers'. With no reviewers, `reviewAndFixLoop` is a
  * plain evaluate-fix loop over the check. `reviewThenFix` re-runs its checks
  * after its fix turn, like the lint gate.
  *
  * The loop runs its checks one at a time each round, after formatting and
  * before the reviewers and the lint gate start, so a check timing or building
  * the code has the machine to itself. A check must not modify sources.
  *
  * Keep a finding's title the same across rounds and put measurements in its
  * description: the loop recognises a finding it already holds as open by title
  * and file.
  */
trait ReviewCheck:
  /** Shown with the check's findings in the run output. */
  def name: String

  def evaluate()(using FlowContext, InStage): ReviewResult
