package orca.review

import orca.agents.{AgentInput, JsonData, given}
import orca.plan.Title

/** The `(name, description)` pair the picker LLM sees for one reviewer.
  *
  * Lives in its own compilation unit (not `ReviewerSelector.scala`) because its
  * `derives JsonData` expands the tapir `Schema` derivation macro, whose
  * generated code does not type-check under `ReviewerSelector.scala`'s
  * `captureChecking` mode (needed there so `prepare` can declare a pure `->`
  * arrow). Keeping the derivation in a plain, non-capture-checked file
  * sidesteps the macro/CC interaction — the same split `FixRequest.scala` uses.
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
