package orca.settings

import munit.FunSuite
import orca.agents.{BackendTag, Effort}

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

  test("parse of harness:model@effort sets the effort"):
    assertEquals(
      AgentSpec.parse("claude:sonnet@high"),
      Right(AgentSpec(BackendTag.ClaudeCode, Some("sonnet"), Some(Effort.High)))
    )

  test("parse of harness@effort sets the effort with no model pin"):
    assertEquals(
      AgentSpec.parse("codex@low"),
      Right(AgentSpec(BackendTag.Codex, None, Some(Effort.Low)))
    )

  test("parse keeps an @ that is not followed by an effort level in the model"):
    assertEquals(
      AgentSpec.parse("claude:claude-sonnet-4@20250514"),
      Right(AgentSpec(BackendTag.ClaudeCode, Some("claude-sonnet-4@20250514")))
    )

  test("parse of a level the harness does not accept is a Left"):
    assertEquals(
      AgentSpec.parse("claude:sonnet@off"),
      Left(
        "claude has no `off` effort; it accepts low, medium, high, xhigh, max"
      )
    )

  test("parse of an effort on gemini is a Left"):
    assertEquals(
      AgentSpec.parse("gemini@high"),
      Left("gemini has no effort setting")
    )

  test("render reads back through parse"):
    val spec =
      AgentSpec(BackendTag.Pi, Some("sonnet:high"), Some(Effort.Minimal))
    assertEquals(AgentSpec.parse(spec.render), Right(spec))
