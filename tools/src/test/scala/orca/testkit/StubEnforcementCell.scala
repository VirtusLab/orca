package orca.testkit

import orca.agents.{
  AutoApprove,
  BackendTag,
  Enforcement,
  EnforcementCell,
  ToolSet,
  TurnDispatch
}
import orca.backend.AgentBackend

/** Mixed into an `AgentBackend` test double that isn't exercising enforcement.
  *
  * `Hard` rather than `Ignored`: the level decides whether `EnforcementNotice`
  * speaks, so an unrelated double declaring a weak level would put a Step into
  * every read-only turn of every suite. The doubles that DO declare something
  * else override `enforcementCell` themselves, which is what makes them read as
  * deliberate.
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
  ): EnforcementCell =
    EnforcementCell(Enforcement.Hard, "test double: nothing to report")
