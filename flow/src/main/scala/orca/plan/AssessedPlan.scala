package orca.plan

import orca.agents.{Announce, JsonData, schemaFromJsonData, codecFromJsonData}

/** Wire shape the LLM produces for an assess-before-plan turn. Flattened
  * (rather than discriminated union) so jsoniter-scala's structured-output path
  * keeps the schema small and easy for the model to fill in. `decision` carries
  * the choice; the other fields are populated according to it.
  *
  *   - `Proceed` → `plan` is set; `rejectKind` / `rejectBody` are ignored.
  *   - `Reject` → `rejectKind` and `rejectBody` are set; `plan` is ignored.
  *
  * An unknown `decision` or `rejectKind` fails decoding, like any other
  * malformed reply. The field combinations are checked post-decode by
  * [[toVerdict]], so the caller can rely on a well-formed [[Verdict]][Plan].
  */
private[plan] case class AssessedPlan(
    decision: AssessedPlan.Decision,
    plan: Option[Plan],
    rejectKind: Option[Verdict.RejectionKind],
    rejectBody: Option[String]
) derives JsonData:

  def toVerdict: Either[String, Verdict[Plan]] = decision match
    case AssessedPlan.Decision.Proceed =>
      plan
        .map(Verdict.Proceed(_))
        .toRight("assess-then-plan returned Proceed but no plan")
    case AssessedPlan.Decision.Reject =>
      for
        body <- rejectBody.toRight(
          "assess-then-plan returned Reject but no rejectBody"
        )
        kind <- rejectKind.toRight(
          "assess-then-plan returned Reject but no rejectKind"
        )
      yield Verdict.Rejection(kind, body)

private[plan] object AssessedPlan:
  enum Decision derives JsonData:
    case Proceed, Reject

  /** Summary surfaced after the assess turn: defers to [[Plan]]'s `Announce` on
    * proceed, surfaces the rejection kind otherwise. Malformed payloads fall
    * through to `None` — [[Plan.autonomous.assessThenPlan]] throws the
    * structured error at the call site.
    */
  given Announce[AssessedPlan] = Announce.fromOption: a =>
    a.toVerdict.toOption.flatMap:
      case Verdict.Proceed(plan) => summon[Announce[Plan]].message(plan)
      case Verdict.Rejection(kind, _) =>
        Some(s"Assessment: rejected (${kind.toString.toLowerCase})")
