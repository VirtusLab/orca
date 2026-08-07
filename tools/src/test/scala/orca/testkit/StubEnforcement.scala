package orca.testkit

import orca.agents.{Enforcement, EnforcementCell}

/** The `enforcementCell` answer for an `AgentBackend` double that isn't the
  * subject of the test.
  *
  * `Hard` rather than `Ignored`: the level decides whether `EnforcementNotice`
  * speaks, so an unrelated double declaring a weak level would put a Step into
  * every read-only turn of every suite. Sharing one constant also makes the few
  * doubles that DO declare something else read as deliberate.
  */
object StubEnforcement:
  val cell: EnforcementCell =
    EnforcementCell(Enforcement.Hard, "test double: nothing to report")
