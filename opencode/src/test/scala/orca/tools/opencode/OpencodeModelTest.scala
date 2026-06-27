package orca.tools.opencode

import orca.OrcaFlowException
import orca.agents.Model

class OpencodeModelTest extends munit.FunSuite:

  test("apply joins provider and model"):
    assertEquals(
      Model.name(OpencodeModel("ollama", "llama3.1")),
      "ollama/llama3.1"
    )

  test("split parses a simple id"):
    assertEquals(
      OpencodeModel.split(Model("anthropic/claude-opus-4-8")),
      ("anthropic", "claude-opus-4-8")
    )

  test("split keeps slashes in the model half (splits on first / only)"):
    assertEquals(
      OpencodeModel.split(Model("lmstudio/google/gemma-3n-e4b")),
      ("lmstudio", "google/gemma-3n-e4b")
    )

  test("split rejects malformed ids (no slash, empty half)"):
    for id <- List("just-a-model", "/model", "provider/") do
      intercept[OrcaFlowException](OpencodeModel.split(Model(id)))

  test("apply rejects an empty providerID"):
    intercept[IllegalArgumentException](OpencodeModel("", "m"))

  test("apply rejects an empty modelID"):
    intercept[IllegalArgumentException](OpencodeModel("p", ""))
