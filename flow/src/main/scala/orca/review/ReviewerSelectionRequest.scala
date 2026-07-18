package orca.review

import orca.agents.{AgentInput, JsonData, given}
import orca.plan.Title

/** The `(name, description)` pair the picker LLM sees for one reviewer.
  *
  * In its own compilation unit because `derives JsonData` expands the tapir
  * `Schema` macro, whose generated code doesn't type-check under
  * `ReviewerSelector.scala`'s `captureChecking` mode (same split as
  * `FixRequest.scala`).
  */
private[review] case class ReviewerInfo(name: String, description: String)
    derives JsonData

/** The request handed to [[ReviewerSelector.agentDriven]]'s picker LLM: the
  * task, the changed files, the eligible reviewers, and the selection brief.
  * Non-CC for the same reason as [[ReviewerInfo]].
  */
private[review] case class ReviewerSelectionRequest(
    taskTitle: Title,
    changedFiles: List[String],
    availableReviewers: List[ReviewerInfo],
    instructions: String
) derives JsonData

private[review] object ReviewerSelectionRequest:
  given AgentInput[ReviewerSelectionRequest] with
    def serialize(r: ReviewerSelectionRequest): String =
      val files = r.changedFiles.map(f => s"  - $f").mkString("\n")
      val reviewers = r.availableReviewers
        .map(ri => s"  - ${ri.name}: ${ri.description}")
        .mkString("\n")
      s"""Task: ${r.taskTitle}
         |
         |Changed files:
         |$files
         |
         |Available reviewers:
         |$reviewers
         |
         |${r.instructions}""".stripMargin
