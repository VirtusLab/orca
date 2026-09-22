package orca.runner

/** Pins that [[WiredAgents.closeAfterScope]] is a best-effort fan-out: one
  * agent's `close()` throwing must not stop the others from being closed (ADR
  * 0018 — a leaked backend resource, e.g. opencode's `serve` process, must
  * never be masked by an earlier agent's failure).
  */
class WiredAgentsTest extends munit.FunSuite:

  test(
    "closeAfterScope closes every agent even when an earlier one's close() throws"
  ):
    var codexClosed = false
    OxCompat.resourceScope:
      WiredAgents.closeAfterScope(
        List(ThrowingClaude, new ClosingCodex(() => codexClosed = true))
      )
    assert(
      codexClosed,
      "codex.close() must run despite claude.close() throwing"
    )

  private object ThrowingClaude extends StubClaudeAgent("throwing-claude"):
    override private[orca] def close(): Unit =
      throw new RuntimeException("boom")
