package orca.plan

import orca.agents.{Announce, JsonData, schemaFromJsonData, codecFromJsonData}

/** Wire shape the LLM produces for a triage turn — a flat record whose `kind`
  * names the [[Triage]] case, plus per-case fields. Flattened (rather than a
  * discriminated union) so jsoniter-scala's structured-output path keeps the
  * schema small and easy for the model to fill in. The field combinations are
  * checked post-decode by [[toTriage]], so callers see a well-formed
  * [[Triage]].
  *
  *   - `NotABug` → `notBugExplanation` is set; the other fields are ignored.
  *   - `Untestable` → `summary` and `reproductionSteps` are set.
  *   - `Testable` → `summary`, `branchName`, and `failingTestPath` are set.
  *
  * Internal to `orca.plan`. Public API is [[Triage]] + [[Triage.interactive]].
  */
private[plan] case class BugTriage(
    kind: BugTriage.Kind,
    notBugExplanation: String,
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
    kind match
      case BugTriage.Kind.NotABug =>
        need("notBugExplanation", notBugExplanation).map(Triage.NotABug.apply)
      case BugTriage.Kind.Untestable =>
        for
          s <- need("summary", summary)
          r <- need("reproductionSteps", reproductionSteps)
        yield Triage.Untestable(s, r)
      case BugTriage.Kind.Testable =>
        for
          s <- need("summary", summary)
          b <- need("branchName", branchName)
          p <- failingTestPath
            .filter(_.trim.nonEmpty)
            .toRight("triage: failingTestPath is missing")
        yield Triage.Testable(s, b, p)

private[plan] object BugTriage:
  enum Kind derives JsonData:
    case NotABug, Untestable, Testable

  /** Defers to [[Triage]]'s own `Announce` — same idiom as [[AssessedPlan]]'s.
    * Malformed payloads fall through to `None`; `Plan.autonomous.triage` throws
    * the structured error at the call site.
    */
  given Announce[BugTriage] = Announce.fromOption: b =>
    b.toTriage.toOption.flatMap(t => summon[Announce[Triage]].message(t))
