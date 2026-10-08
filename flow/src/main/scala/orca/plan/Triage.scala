package orca.plan

import orca.agents.{Announce, JsonData, schemaFromJsonData, codecFromJsonData}

/** Outcome of triaging a request — a bug report, a feature request, or any
  * other ask — against the codebase. `Reject` carries the reply to send back to
  * whoever asked. `Accept` means the work should be done: `brief` is what
  * triage verified (files involved, root cause when known), for seeding the
  * planner, and `kind` says what has to happen before planning.
  */
enum Triage derives JsonData:
  case Reject(reply: String)
  case Accept(summary: String, brief: String, kind: Triage.Kind)

object Triage:
  enum Kind derives JsonData:
    /** A defect a focused test can show; the test goes at `failingTestPath`. */
    case TestableBug(failingTestPath: String)

    /** A defect no focused test can show (UI-only, races, environment). */
    case UntestableBug(reproductionSteps: String)

    /** Not a defect: a feature or other change. */
    case Change

  given Announce[Triage] = Announce.from:
    case Reject(_) => "Triage: rejected"
    case Accept(summary, _, Kind.TestableBug(path)) =>
      s"Triage: bug — $summary; failing test at $path"
    case Accept(summary, _, Kind.UntestableBug(_)) =>
      s"Triage: bug — $summary; no automated reproduction"
    case Accept(summary, _, Kind.Change) => s"Triage: change — $summary"
