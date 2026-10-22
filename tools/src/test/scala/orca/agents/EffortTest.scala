package orca.agents

import orca.testkit.{ScriptedBackend, TestAgent}

class EffortTest extends munit.FunSuite:

  test("withEffort pins the level"):
    val agent = TestAgent(ScriptedBackend.unused(BackendTag.Pi))
    assertEquals(
      agent.withEffort(Effort.Low).config.effort,
      Some(Effort.Low)
    )

  test("claude refuses Off"):
    val agent = TestAgent(ScriptedBackend.unused(BackendTag.ClaudeCode))
    intercept[IllegalArgumentException](agent.withEffort(Effort.Off))

  test("pi refuses Max"):
    val agent = TestAgent(ScriptedBackend.unused(BackendTag.Pi))
    intercept[IllegalArgumentException](agent.withEffort(Effort.Max))

  test("withEffort does not compile on gemini"):
    val errors = compileErrors(
      "TestAgent(ScriptedBackend.unused(BackendTag.Gemini)).withEffort(Effort.Low)"
    )
    assert(errors.contains("This harness has no effort setting."), errors)
