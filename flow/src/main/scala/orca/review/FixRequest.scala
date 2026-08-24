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
    issues: List[KeyedIssue]
) derives JsonData

/** A finding paired with the key the fixer is asked to echo for it. Minted when
  * the gate splits an agent's findings, then used by both the display and the
  * fix prompt, so what the fixer names in its prose ("Fix I2.1") is what the
  * reader saw on screen.
  */
private[review] case class KeyedIssue(key: String, issue: ReviewIssue)
    derives JsonData

private[review] object KeyedIssue:
  /** Mint the keys for one agent's findings in a round.
    *
    * Keys are positional and minted per turn, so the fixer copies them exactly
    * where it would paraphrase a title; [[FixOutcome.reconcile]] resolves
    * echoes against them. Numbering runs per agent rather than across the round
    * so a key is fixed before the round's agents run: they report in any order,
    * and each one's findings are shown as soon as it finishes.
    */
  def forAgent(agentIndex: Int, issues: List[ReviewIssue]): List[KeyedIssue] =
    issues.zipWithIndex.map((issue, n) =>
      KeyedIssue(s"I${agentIndex + 1}.${n + 1}", issue)
    )

private[review] object FixRequest:
  given AgentInput[FixRequest] with
    def serialize(r: FixRequest): String =
      val formatted =
        r.issues.map(k => renderIssue(k.key, k.issue)).mkString("\n\n")
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
