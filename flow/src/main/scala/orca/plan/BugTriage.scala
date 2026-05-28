package orca.plan

import orca.llm.JsonData

/** Wire shape the LLM produces for a triage turn — a flat record with a boolean
  * discriminator (`isBug`) plus per-branch fields. Flattened (rather than a
  * discriminated union) so jsoniter-scala's structured-output path keeps the
  * schema small and easy for the model to fill in. The contract is enforced
  * post-decode by [[toTriage]], which throws on incoherent combinations so
  * callers see a well-formed [[Triage]].
  *
  *   - `isBug == false` → `notBugExplanation` is set; the other fields are
  *     ignored.
  *   - `isBug == true`, `canTest == false` → `summary` and `reproductionSteps`
  *     are set.
  *   - `isBug == true`, `canTest == true` → `summary`, `branchName`, and
  *     `failingTestPath` are set.
  *
  * Internal to `orca.plan`. Public API is [[Triage]] + [[Triage.interactive]].
  */
private[plan] case class BugTriage(
    isBug: Boolean,
    notBugExplanation: String,
    canTest: Boolean,
    reproductionSteps: String,
    failingTestPath: Option[String],
    branchName: String,
    summary: String
) derives JsonData:

  def toTriage: Either[String, Triage] =
    if !isBug then
      if notBugExplanation.trim.isEmpty then
        Left("triage: isBug=false but notBugExplanation is empty")
      else Right(Triage.NotABug(notBugExplanation))
    else if !canTest then
      if summary.trim.isEmpty then
        Left("triage: isBug=true but summary is empty")
      else if reproductionSteps.trim.isEmpty then
        Left("triage: canTest=false but reproductionSteps is empty")
      else Right(Triage.Untestable(summary, reproductionSteps))
    else
      failingTestPath.filter(_.trim.nonEmpty) match
        case None =>
          Left("triage: canTest=true but failingTestPath is missing")
        case Some(path) =>
          if summary.trim.isEmpty then
            Left("triage: isBug=true but summary is empty")
          else if branchName.trim.isEmpty then
            Left("triage: canTest=true but branchName is empty")
          else Right(Triage.Testable(summary, branchName, path))
