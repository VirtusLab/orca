package orca.settings

import munit.FunSuite
import orca.agents.BackendTag

class AgentSpecTest extends FunSuite:

  test("parse of a bare harness name yields no model pin"):
    assertEquals(
      AgentSpec.parse("claude"),
      Right(AgentSpec(BackendTag.ClaudeCode, None))
    )

  test("parse of harness:model splits at the colon"):
    assertEquals(
      AgentSpec.parse("claude:opus"),
      Right(AgentSpec(BackendTag.ClaudeCode, Some("opus")))
    )

  test("parse keeps a model id containing dots and digits verbatim"):
    assertEquals(
      AgentSpec.parse("gemini:gemini-2.5-pro"),
      Right(AgentSpec(BackendTag.Gemini, Some("gemini-2.5-pro")))
    )

  test("parse of the empty string is a Left"):
    assert(AgentSpec.parse("").isLeft)

  test("parse of an unknown harness is a Left listing the valid names"):
    AgentSpec.parse("unknown:x") match
      case Left(problem) =>
        AgentSpec.harnessNames.keys.foreach: valid =>
          assert(
            problem.contains(valid),
            s"should list valid harness `$valid`: $problem"
          )
      case Right(spec) => fail(s"expected a parse error, got: $spec")

  test("parse of a trailing colon with no model is equivalent to no pin"):
    assertEquals(
      AgentSpec.parse("claude:"),
      Right(AgentSpec(BackendTag.ClaudeCode, None))
    )
