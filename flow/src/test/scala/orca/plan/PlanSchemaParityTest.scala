package orca.plan

import orca.json.given

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
