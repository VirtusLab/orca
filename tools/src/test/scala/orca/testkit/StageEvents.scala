package orca.testkit

import orca.StagePath
import orca.events.{OrcaEvent, StageOutcome}

/** Test shorthand for the markers of a stage named `name` that completes. Every
  * path is a direct child of the flow body, even when a test nests these;
  * listeners build their stage stack from event order, not from paths.
  */
object StageEvents:
  def started(name: String): OrcaEvent.StageStarted =
    OrcaEvent.StageStarted(path(name))

  def ended(name: String): OrcaEvent.StageEnded =
    OrcaEvent.StageEnded(path(name), StageOutcome.Completed)

  private def path(name: String): StagePath.Stage =
    StagePath.FlowBody.child(name, 0)
