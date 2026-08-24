# Opencode's Hard read-only cell ignores the `task` tool, which is in today's measured roster

**Aspect**: correctness  **Severity**: medium

## Problem

The opencode read-only gate disables exactly four tools —
`opencode/src/main/scala/orca/tools/opencode/OpencodeArgs.scala:89-97`:

```scala
case ToolSet.ReadOnly | ToolSet.NetworkOnly =>
  Map("write" -> false, "edit" -> false, "bash" -> false, "patch" -> false)
```

and the cell (`OpencodeArgs.scala:118-121`) classifies this `Hard` with the
rationale "the server offers none of those four — unlike an allowlist, this
stays exact only while opencode ships no fifth writing tool".

But the caveat is about a *future* fifth tool, while `task` — a subagent
spawner — is in **today's** measured default roster, recorded in
`adr/0014-opencode-server-driver.md` (~line 487): "Default tool set (server
'build' agent): `bash, edit, glob, grep, read, skill, task, todowrite,
webfetch, write`". A subagent spawned via `task` runs with the agent profile's
default (write-capable) tool set unless the per-message `tools:{…}` flags
propagate into subagents — which no probe in ADR 0014 establishes.

This is a consistency gap, not a security hole (Orca's threat model is
trusted-but-fallible agents): if the flags don't propagate, a read-only
opencode reviewer that drifts into `task` gets writes back through the
subagent, while the cell claims `Hard` — and `EnforcementNotice` stays silent,
because a `Hard` cell reports nothing
(`tools/src/main/scala/orca/agents/EnforcementNotice.scala:104-106`).

## Proposed solution

Cheapest fix, no probe needed: add `"task" -> false` to the read-only
`writeGate` in `OpencodeArgs.toolFlags`, and adjust the cell rationale to name
five tools. (`todowrite` writes only agent-internal todo state, not the
workspace — leave it enabled.)

Alternative, if losing `task` on read-only turns matters: probe whether the
per-message `tools` flags propagate into `task` subagents (same probe style as
ADR 0014's other measurements) and record the dated result in the cell
rationale, like the codex/claude probe citations
(`CodexArgs.scala:204`, `ClaudeArgs.scala:204`).

Test: extend `opencode/src/test/scala/orca/tools/opencode/DefaultOpencodeToolTest.scala`'s
message-body assertion for a read-only turn to pin the disabled set including
`task`.

Must NOT change: the `Hard` level itself (with `task` disabled it becomes
accurate), the `question` gating, or the `Full` tier's empty gate.

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; wrong test target corrected).

Checked OpencodeArgs.scala:89-98 (four-tool gate) and :118-121 (Hard cell rationale — exact); adr/0014-opencode-server-driver.md:487-488 (measured roster includes `task` and `webfetch`); EnforcementNotice.scala:104-106 (`consequenceOf(Hard) = None`, so a Hard cell reports nothing). Threat-model framing is explicitly honest-mistake — passes.

Solution revision — the test target is wrong: `DefaultOpencodeToolTest.scala` contains no message-body assertions (it drives a fake backend). The gate is pinned in `opencode/src/test/scala/orca/tools/opencode/OpencodeArgsTest.scala` ("read-only turn disables the write tools (write/edit/bash/patch)", lines 107-121). Replace the test instruction with: extend that OpencodeArgsTest assertion to include `task` (and rename the test accordingly).

Ordering: same lines as finding 03 — land 02+03 as one change, before 01 restructures OpencodeArgs.
