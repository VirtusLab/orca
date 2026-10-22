package orca.pr

import orca.json.given

@scala.annotation.experimental
class PrSchemaParityTest extends orca.json.SchemaParitySuite:
  parity[PushResult]
  parity[CreateResult]
  parity[PrSummary]
