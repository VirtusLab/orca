package orca.review

import orca.agents.{AgentInput, JsonData, given}

/** The fix instruction plus the issues handed to the coding agent each round.
  *
  * Lives in its own compilation unit (not `ReviewLoop.scala`) because its
  * `derives JsonData` expands the tapir `Schema` macro, whose generated code
  * does not type-check under `ReviewLoop.scala`'s `captureChecking` mode
  * (needed for the reviewer fan-out — ADR 0018 §6).
  */
private[review] case class FixRequest(
    instructions: String,
    issues: List[ReviewIssue]
) derives JsonData

private[review] object FixRequest:
  /** The key the fixer is asked to echo for the issue at `index`. Positional
    * and minted per turn, so it copies exactly where a paraphrased title does
    * not; [[FixOutcome.reconcile]] resolves echoes against it.
    */
  def key(index: Int): String = s"I${index + 1}"

  given AgentInput[FixRequest] with
    def serialize(r: FixRequest): String =
      val formatted =
        r.issues.zipWithIndex
          .map((i, n) => renderIssue(key(n), i))
          .mkString("\n\n")
      // No `stripMargin`: a reviewer's description or suggestion can carry
      // markdown tables and `|`-margin blocks, which it would eat.
      s"${r.instructions}\n\nIssues to fix:\n$formatted"

  /** One issue as the fixer sees it. Deliberately not [[formatIssue]], the
    * display rendering: the fixer needs the description, which the screen form
    * omits.
    */
  private def renderIssue(key: String, issue: ReviewIssue): String =
    // Exhaustive destructure: a new `ReviewIssue` field stops compiling here
    // until this prompt decides what to do with it. `confidence` is left out —
    // the gate has already applied it, and the number would only invite the
    // fixer to re-litigate the finding.
    val ReviewIssue(severity, _, title, description, location, suggestion) =
      issue
    val lines = List(
      Some(s"$key [$severity] $title"),
      locationLine(location),
      Option.when(description.nonEmpty)(s"    $description"),
      suggestion.map(s => s"    suggestion: $s")
    )
    lines.flatten.mkString("\n")
