package orca.plan

import orca.agents.Announce
import orca.json.{JsonData, schemaFromJsonData, codecFromJsonData}

/** Wire shape of a triage turn: a flat record whose `kind` names the outcome,
  * plus per-outcome fields. Flat rather than a discriminated union so the
  * structured-output schema stays small for the model; [[toTriage]] checks the
  * field combination.
  *
  *   - `Reject` → `reply`.
  *   - `TestableBug` → `summary`, `brief`, `failingTestPath`.
  *   - `UntestableBug` → `summary`, `brief`, `reproductionSteps`.
  *   - `Change` → `summary`, `brief`.
  */
private[plan] case class TriageReply(
    kind: TriageReply.Kind,
    reply: String,
    summary: String,
    brief: String,
    failingTestPath: String,
    reproductionSteps: String
) derives JsonData:

  def toTriage: Either[String, Triage] =
    def need(field: String, value: String): Either[String, String] =
      Either.cond(value.trim.nonEmpty, value, s"triage: $field is empty")
    def accept(kind: Triage.Kind): Either[String, Triage] =
      for
        s <- need("summary", summary)
        b <- need("brief", brief)
      yield Triage.Accept(summary = s, brief = b, kind = kind)
    kind match
      case TriageReply.Kind.Reject => need("reply", reply).map(Triage.Reject(_))
      case TriageReply.Kind.TestableBug =>
        need("failingTestPath", failingTestPath)
          .flatMap(p => accept(Triage.Kind.TestableBug(p)))
      case TriageReply.Kind.UntestableBug =>
        need("reproductionSteps", reproductionSteps)
          .flatMap(r => accept(Triage.Kind.UntestableBug(r)))
      case TriageReply.Kind.Change => accept(Triage.Kind.Change)

private[plan] object TriageReply:
  enum Kind derives JsonData:
    case Reject, TestableBug, UntestableBug, Change

  /** Defers to [[Triage]]'s own `Announce`; a malformed payload announces
    * nothing, and `Plan.*.triage` throws the structured error at the call site.
    */
  given Announce[TriageReply] = Announce.fromOption: r =>
    r.toTriage.toOption.flatMap(t => summon[Announce[Triage]].message(t))
