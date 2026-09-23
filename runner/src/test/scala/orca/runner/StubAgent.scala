package orca.runner

import orca.agents.{Agent, BackendTag, ClaudeAgent, CodexAgent}
import orca.testkit.{ScriptedBackend, TestAgent}

/** Agents for tests that assert wiring/lifecycle, not LLM behaviour: each is a
  * fresh agent on its own backend, and every turn fails — no test reaches one.
  * Fresh per call, since closing a run latches its agents' backends.
  */
object StubAgent:
  def claude: ClaudeAgent = of(BackendTag.ClaudeCode)
  def codex: CodexAgent = of(BackendTag.Codex)

  def of[B <: BackendTag & Singleton](tag: B): Agent[B] =
    TestAgent(ScriptedBackend.unused(tag))
