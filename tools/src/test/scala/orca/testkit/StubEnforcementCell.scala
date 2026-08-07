package orca.testkit

import orca.agents.{
  AutoApprove,
  BackendTag,
  EnforcementCell,
  ToolSet,
  TurnDispatch
}
import orca.backend.AgentBackend

/** Mixed into an `AgentBackend` test double that isn't exercising enforcement,
  * in place of hand-writing the [[StubEnforcement.cell]] answer.
  *
  * Extends `AgentBackend` so `enforcementCell` is a real `override` — as a
  * standalone trait's plain method it would warn on its three unused
  * parameters. A double still names `AgentBackend` among its own parents: a
  * trait cannot pass that trait's parameters.
  */
trait StubEnforcementCell[B <: BackendTag] extends AgentBackend[B]:
  override def enforcementCell(
      tools: ToolSet,
      autoApprove: AutoApprove,
      dispatch: TurnDispatch
  ): EnforcementCell = StubEnforcement.cell
