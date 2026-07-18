package orca.runner

import orca.agents.ClaudeAgent

/** A `ClaudeAgent` stub for tests that assert wiring/lifecycle, not LLM
  * behaviour. Every call throws — no test reaches one.
  */
object StubAgent:
  val claude: ClaudeAgent = new StubClaudeAgent("stub") {}
