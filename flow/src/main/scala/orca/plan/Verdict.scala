package orca.plan

/** Outcome of an assess-before-act stage (e.g. [[Plan.autonomous.assessThenPlan]]).
  * Either the agent endorses the input and supplies a value to act on
  * (`Proceed`), or it rejects the input with a body the caller surfaces to
  * whoever filed the report — a follow-up question, a constructive critique,
  * or an outright rebuff. The `kind` lets the caller pick framing (e.g. a GH
  * comment template) without re-reading the body.
  *
  * Generic so the same type works for plan-or-critique, design-doc-or-pushback,
  * triage-or-defer, etc.
  */
sealed trait Verdict[+A]

object Verdict:
  case class Proceed[+A](value: A) extends Verdict[A]

  enum RejectionKind:
    /** The reporter likely has the right idea but a key detail is missing
      * (repro steps, target file, intended behaviour). Caller should phrase the
      * surfaced text as a question back to the reporter.
      */
    case Question

    /** The report holds up as a problem, but the proposed framing has gaps or
      * contradictions worth raising before any fix lands. Caller should phrase
      * the surfaced text as constructive feedback.
      */
    case Critique

    /** The report doesn't hold up against the repo (no such file, duplicate,
      * contradicted by existing code, out of scope). Caller should phrase the
      * surfaced text as a polite decline.
      */
    case Rebuff

  case class Rejection(kind: RejectionKind, body: String) extends Verdict[Nothing]
