package orca.runner

import orca.agents.ClaudeAgent

/** A `ClaudeAgent` stub for tests that must pass the now-mandatory leading
  * model to `flow(...)` (ADR 0018 §2.5) but assert wiring/lifecycle, not LLM
  * behaviour. Every call throws — no test reaches one.
  */
object StubAgent:
  val claude: ClaudeAgent = new StubClaudeAgent("stub") {}
