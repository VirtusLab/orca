package orca

import orca.json.{JsonData, given}

// One suite per package: some of the types are package-private.

package plan:
  @scala.annotation.experimental
  class PlanSchemaParityTest extends orca.json.SchemaParitySuite:
    parity[BugReportMatch]
    parity[Plan]
    parity[Epic]
    parity[Roadmap]
    parity[Task]
    parity[TriageReply]
    parity[Triage]
    parity[Triage.Kind]

package review:
  @scala.annotation.experimental
  class ReviewSchemaParityTest extends orca.json.SchemaParitySuite:
    parity[Location]
    parity[ReviewFinding]
    parity[OpenReason]
    parity[OpenFinding]
    parity[OpenFindings]
    parity[ReviewerFindings]
    parity[ReviewReport]
    parity[ReviewResult]
    parity[FixOutcome]
    parity[DeclinedFinding]
    parity[ReviewerInfo]
    parity[ReviewerSelectionRequest]
    // The given adds a validator on top of the derivation.
    parity[SelectedReviewers](using
      JsonData.derived[SelectedReviewers].schema,
      summon[scala.deriving.Mirror.Of[SelectedReviewers]]
    )
    parity[FixRequest]
    parity[KeyedFinding]

package progress:
  @scala.annotation.experimental
  class ProgressSchemaParityTest extends orca.json.SchemaParitySuite:
    parity[ProgressHeader]
    parity[StageEntry]
    parity[StageStart]
    parity[ProgressLog]
    parity[FlowSource]
    parity[PublishedWork]

package pr:
  @scala.annotation.experimental
  class PrSchemaParityTest extends orca.json.SchemaParitySuite:
    parity[PushResult]
    parity[CreateResult]
    parity[PrSummary]
