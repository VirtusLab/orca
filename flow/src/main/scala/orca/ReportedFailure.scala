package orca

import orca.util.TextUtil

/** A failure already shown to the user as an `OrcaEvent.Error`: enclosing
  * stages and the flow boundary pass it on without reporting it again. Thrown
  * by `stage`, `fail` and the flow lifecycle phases; `cause` is the original
  * failure, whose stack trace says where it happened, so this one records none.
  */
private[orca] final class ReportedFailure(val cause: Throwable)
    extends RuntimeException(
      TextUtil.throwableMessage(cause),
      cause,
      true,
      false
    )

private[orca] object ReportedFailure:
  /** `e` as a reported failure: returned as is when it already is one,
    * otherwise passed to `report` first and wrapped.
    */
  def reportOnce(e: Throwable)(report: Throwable => Unit): ReportedFailure =
    e match
      case reported: ReportedFailure => reported
      case _ =>
        report(e)
        ReportedFailure(e)
