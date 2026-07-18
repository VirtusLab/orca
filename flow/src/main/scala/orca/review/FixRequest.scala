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
  given AgentInput[FixRequest] with
    def serialize(r: FixRequest): String =
      val formatted = r.issues.map(formatIssue).mkString("\n")
      s"""${r.instructions}
         |
         |Issues to fix:
         |$formatted""".stripMargin
