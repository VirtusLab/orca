package orca.plan

import orca.llm.{Announce, JsonData, schemaFromJsonData, codecFromJsonData}

/** Wire shape the LLM produces for an assess-before-plan turn. Flattened
  * (rather than discriminated union) so jsoniter-scala's structured-output path
  * keeps the schema small and easy for the model to fill in. `verdict` carries
  * the choice; the other fields are populated according to it.
  *
  *   - `verdict == "proceed"` → `plan` is set; `rejectKind` / `rejectBody` are
  *     ignored.
  *   - `verdict == "reject"` → `rejectKind` and `rejectBody` are set; `plan` is
  *     ignored.
  *
  * The contract is enforced post-decode by [[toVerdict]], which throws on
  * malformed combinations so the caller can rely on a well-formed
  * [[Verdict]][Plan].
  */
private[plan] case class AssessedPlan(
    verdict: String,
    plan: Option[Plan],
    rejectKind: Option[String],
    rejectBody: Option[String]
) derives JsonData:

  def toVerdict: Either[String, Verdict[Plan]] = verdict match
    case "proceed" =>
      plan match
        case Some(p) => Right(Verdict.Proceed(p))
        case None =>
          Left("assess-then-plan returned verdict=proceed but no plan")
    case "reject" =>
      for
        body <- rejectBody.toRight(
          "assess-then-plan returned verdict=reject but no rejectBody"
        )
        kindStr <- rejectKind.toRight(
          "assess-then-plan returned verdict=reject but no rejectKind"
        )
        kind <- kindStr match
          case "question" => Right(Verdict.RejectionKind.Question)
          case "critique" => Right(Verdict.RejectionKind.Critique)
          case "rebuff"   => Right(Verdict.RejectionKind.Rebuff)
          case other =>
            Left(s"assess-then-plan: unknown rejectKind '$other'")
      yield Verdict.Rejection(kind, body)
    case other =>
      Left(s"assess-then-plan: unknown verdict '$other'")

private[plan] object AssessedPlan:
  /** Friendly summary surfaced via `StructuredResult` after the assess turn.
    * Defers to [[Plan]]'s own `Announce` on proceed; on reject it surfaces the
    * kind so the event log shows why no PR happened. Malformed payloads
    * (`toVerdict` Left) silently fall through to `None` here —
    * [[Plan.autonomous.assessThenPlan]] throws the structured error at the call
    * site, so the log just stays quiet about the rendering.
    */
  given Announce[AssessedPlan] = Announce.fromOption: a =>
    a.toVerdict.toOption.flatMap:
      case Verdict.Proceed(plan) => summon[Announce[Plan]].message(plan)
      case Verdict.Rejection(kind, _) =>
        Some(s"Assessment: rejected (${kind.toString.toLowerCase})")
