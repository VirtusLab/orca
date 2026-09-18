package orca.agents

import munit.FunSuite
import orca.StagePath

/** How a [[SessionKey]] renders in a run's own diagnostics. */
class SessionKeyTest extends FunSuite:

  test("a flow-body key describes as the bare name"):
    assertEquals(
      SessionKey("implementer", StagePath.FlowBody).describe,
      "'implementer'"
    )

  test("a stage key describes with the minting stage's path id"):
    assertEquals(
      SessionKey(
        "implementer",
        StagePath.Stage("Task: add multiply#0")
      ).describe,
      "'implementer' in stage 'Task: add multiply#0'"
    )
