package orca.testkit

import orca.StagePath
import orca.events.{OrcaEvent, StageOutcome}

/** Test shorthand for the markers of a stage named `name` opened at the flow
  * body. Listeners key nothing on the path, so a test nesting these still sees
  * the stack it builds.
  */
object StageEvents:
  def started(name: String): OrcaEvent.StageStarted =
    OrcaEvent.StageStarted(path(name), name)

  def ended(
      name: String,
      outcome: StageOutcome = StageOutcome.Completed
  ): OrcaEvent.StageEnded =
    OrcaEvent.StageEnded(path(name), outcome)

  private def path(name: String): StagePath.Stage =
    StagePath.FlowBody.child(name, 0)
