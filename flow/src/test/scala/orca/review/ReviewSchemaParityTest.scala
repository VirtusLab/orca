package orca.review

import orca.json.{JsonData, given}

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
