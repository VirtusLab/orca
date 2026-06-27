package orca.plan

import orca.agents.JsonData

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
    def need(field: String, value: String): Either[String, String] =
      Either.cond(
        value.trim.nonEmpty,
        value,
        s"triage: $field is empty"
      )
    (isBug, canTest) match
      case (false, _) =>
        need("notBugExplanation", notBugExplanation).map(Triage.NotABug.apply)
      case (true, false) =>
        for
          s <- need("summary", summary)
          r <- need("reproductionSteps", reproductionSteps)
        yield Triage.Untestable(s, r)
      case (true, true) =>
        for
          s <- need("summary", summary)
          b <- need("branchName", branchName)
          p <- failingTestPath
            .filter(_.trim.nonEmpty)
            .toRight("triage: failingTestPath is missing")
        yield Triage.Testable(s, b, p)
