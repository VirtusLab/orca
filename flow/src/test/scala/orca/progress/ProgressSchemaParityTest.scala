package orca.progress

import orca.json.given

@scala.annotation.experimental
class ProgressSchemaParityTest extends orca.json.SchemaParitySuite:
  parity[ProgressHeader]
  parity[StageEntry]
  parity[StageStart]
  parity[ProgressLog]
  parity[FlowSource]
  parity[PublishedWork]
