package orca.plan

import orca.agents.{Announce, JsonData}

/** The agent's verdict on whether a CI failure (or other reproduction artefact)
  * actually matches the original bug report. Used after CI comes back red to
  * confirm we're chasing the right defect before implementing a fix.
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
