package orca.plan

import orca.agents.Announce
import orca.json.JsonData

/** The agent's verdict on whether a failing test's output reproduces the
  * original report. Used after the reproduction test runs, before any fix is
  * planned.
  */
case class BugReportMatch(
    /** Whether the failing-test output (or reproduction artefact) is a faithful
      * reproduction of what the original report described.
      */
    matches: Boolean,
    /** Short justification for the verdict. */
    explanation: String
) derives JsonData

object BugReportMatch:
  given Announce[BugReportMatch] = Announce.from: m =>
    val verdict =
      if m.matches then "Reproduction confirmed" else "Reproduction MISMATCH"
    s"$verdict: ${m.explanation}"
