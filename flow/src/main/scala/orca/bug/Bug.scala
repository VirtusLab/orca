package orca.bug

import orca.{Announce, JsonData}

/** Outcome of an interactive triage turn against a bug report. The agent
  * decides whether the bug can be reproduced as a focused unit test and picks a
  * branch name + PR title; flow scripts use the structured fields to drive the
  * rest of the bugfix loop (where to write the failing artefact, what to
  * commit, what to push).
  */
case class BugTriage(
    /** True if a focused unit test can reproduce the bug. */
    canTest: Boolean,
    /** Free-form reproduction steps the agent inferred from the report — used
      * when `canTest` is false (we can't write a test, so we document instead).
      */
    reproductionSteps: String,
    /** Path of the test file the agent will create when `canTest` is true. None
      * otherwise.
      */
    failingTestPath: Option[String],
    /** Short branch name. Lowercase kebab-case; no spaces. */
    branchName: String,
    /** One-line summary suitable for the PR title. */
    summary: String
) derives JsonData

object BugTriage:
  given Announce[BugTriage] = Announce.from: t =>
    val approach =
      if t.canTest then s"failing test at ${t.failingTestPath.getOrElse("?")}"
      else "documenting reproduction steps"
    s"Triage: ${t.summary} — $approach on branch '${t.branchName}'"

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
