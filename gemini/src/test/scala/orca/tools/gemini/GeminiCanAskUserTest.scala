package orca.tools.gemini

import orca.agents.{BackendTag, CanAskUser}

class GeminiCanAskUserTest extends munit.FunSuite:

  test("Gemini exposes the CanAskUser capability"):
    // Regression: gemini's interactive path wires the ask_user MCP bridge, so a
    // `CanAskUser[BackendTag.Gemini.type]` given must exist — otherwise
    // CanAskUser-constrained helpers (e.g. `Plan.interactive.from(gemini, …)`)
    // fail to COMPILE for gemini. This summon only type-checks when the given
    // is present.
    val _ = summon[CanAskUser[BackendTag.Gemini.type]]
