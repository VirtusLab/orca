# Opencode is the only backend whose ReadOnly tier keeps network access

**Aspect**: correctness  **Severity**: medium

## Problem

`OpencodeArgs.toolFlags`
(`opencode/src/main/scala/orca/tools/opencode/OpencodeArgs.scala:85-104`)
produces the identical gate for `ReadOnly` and `NetworkOnly`
(`write/edit/bash/patch` disabled), so `webfetch` — in the measured default
roster, `adr/0014-opencode-server-driver.md` ~line 487-488 — stays enabled on
**ReadOnly** turns.

This contradicts two documented statements:

- `adr/0016-toolset-capability-axis-and-planner-network.md` (Context, lines
  9-13): "On every backend the read-only mode also blocks the network" —
  listing claude/codex/pi/gemini mechanisms. Opencode is the silent exception.
- The intent stated at `claude/src/main/scala/orca/tools/claude/ClaudeBackend.scala:412-413`:
  "`NetworkOnly` only — reviewers stay network-free."

And the `toolFlags` comment (`OpencodeArgs.scala:83-84`) justifies collapsing
the tiers with "scoped network would need `bash` enabled" — which overlooks
`webfetch`, a network read tool that needs no shell.

Consequence (consistency, not security): a `buildReviewers` reviewer on
opencode retains web reads while the same reviewer on claude/codex/pi does not,
and the ReadOnly/NetworkOnly distinction collapses in the permissive direction
with no scaladoc saying so.

## Proposed solution

In `OpencodeArgs.toolFlags`, split the two tiers:

- `ReadOnly` → also disable `webfetch` (add `"webfetch" -> false`).
- `NetworkOnly` → keep `webfetch` enabled (the current gate).

This simultaneously makes opencode's `NetworkOnly` a real tier — web reads, no
shell, the same shape as claude's — instead of an alias for `ReadOnly`.
Rewrite the `toolFlags` comment accordingly (drop the "would need `bash`"
argument), and revisit the two cells' rationales: `NetworkOnly` may now deserve
its own rationale sentence naming `webfetch`. The levels stay `Hard` for both
(the write gate is unchanged; see also finding 02 for `task`).

Tests: pin both tiers' disabled sets in
`opencode/src/test/scala/orca/tools/opencode/DefaultOpencodeToolTest.scala`
(one test per tier, per the one-scenario rule). If the AGENTS.md rendered
table changes (it should not — levels are unchanged), paste what
`EnforcementTableTest` prints rather than hand-editing.

Must NOT change: the `question` gating, the `Full` tier, or ADR 0014/0016
history (state present behavior only).

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; test target corrected, extra required edits added).

Checked OpencodeArgs.scala:81-84 (the "scoped network would need `bash` enabled" comment — verbatim, and it does overlook `webfetch`) and :91-97 (identical gate for both tiers); ADR 0016 lines 9-13 ("On every backend the read-only mode also blocks the network" — opencode silently absent); ClaudeBackend.scala:412-413. All exact.

Solution revision: (a) the per-tier pins go in `OpencodeArgsTest.scala`, not `DefaultOpencodeToolTest.scala` (which has no message-body assertions). (b) The existing test "NetworkOnly keeps bash disabled (no writable-shell network)" (OpencodeArgsTest.scala:122-132) carries a comment stating "opencode has no scoped network: NetworkOnly gates the same write tools as ReadOnly" — rewrite it when the tiers split. (c) The shared `ReadOnly | NetworkOnly` cell arm must split into two arms (the disabled sets now differ) — rationale text only; levels stay Hard, so no AGENTS.md regeneration.

Ordering: land as one change with 02, before 01.
