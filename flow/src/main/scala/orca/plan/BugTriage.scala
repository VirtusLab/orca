package orca.plan

import orca.llm.{Announce, JsonData}

/** Outcome of an interactive triage turn against a bug report. The agent first
  * decides whether the report describes an actual defect (`isBug`); if so, it
  * picks a reproduction strategy (focused unit test vs. free-form repro steps)
  * and a branch name + PR title for the rest of the bugfix flow.
  *
  * Field combinations the agent should produce:
  *
  *   - `isBug = false` → set `notBugExplanation`; leave the other fields with
  *     reasonable empty defaults. The flow surfaces the explanation back on
  *     the original issue and stops.
  *   - `isBug = true`, `canTest = false` → set `reproductionSteps`; the flow
  *     posts them on the issue and stops (no PR for docs-only repros).
  *   - `isBug = true`, `canTest = true` → set `failingTestPath`, `branchName`,
  *     `summary`. The flow writes the failing test, opens a PR, and proceeds.
  */
case class BugTriage(
    /** True if the report describes an actual defect (not intended behavior,
      * user error, or out-of-scope). When false, only `notBugExplanation` is
      * meaningful.
      */
    isBug: Boolean,
    /** Short explanation to post back on the issue when `isBug` is false. */
    notBugExplanation: String,
    /** True if a focused unit test can reproduce the bug. Only meaningful when
      * `isBug` is true.
      */
    canTest: Boolean,
    /** Free-form reproduction steps the agent inferred from the report — used
      * when `isBug` is true but `canTest` is false (we can't write a test, so
      * we document instead).
      */
    reproductionSteps: String,
    /** Path of the test file the agent will create when `canTest` is true. */
    failingTestPath: Option[String],
    /** Short branch name. Lowercase kebab-case; no spaces. Only used when
      * `isBug` and `canTest` are both true.
      */
    branchName: String,
    /** One-line summary suitable for the PR title. */
    summary: String
) derives JsonData

object BugTriage:
  given Announce[BugTriage] = Announce.from: t =>
    if !t.isBug then s"Not a bug: ${t.notBugExplanation}"
    else if !t.canTest then
      s"Triage: ${t.summary} — documenting reproduction (no PR)"
    else
      s"Triage: ${t.summary} — failing test at ${t.failingTestPath
          .getOrElse("?")} on branch '${t.branchName}'"
