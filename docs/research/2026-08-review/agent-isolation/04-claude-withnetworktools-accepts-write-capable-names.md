# `claude.withNetworkTools` silently voids the NetworkOnly Hard cell when given a write-capable tool name

**Aspect**: correctness  **Severity**: medium

## Problem

`ClaudeBackend.withNetworkTools`
(`claude/src/main/scala/orca/tools/claude/ClaudeBackend.scala:92-109`)
validates only *shape* — `tools.filterNot(ClaudeBackend.BareToolName.matches)`
rejects command-scoped entries like `"Bash(gh api:*)"`, but `"Bash"`,
`"Write"`, `"Edit"` are bare names and pass.

On a `NetworkOnly` turn those names then join **both** flags
(`claude/src/main/scala/orca/tools/claude/ClaudeArgs.scala:134-136`):

```scala
case ToolSet.NetworkOnly =>
  Seq("--tools", (ReadOnlyTools ++ networkTools).mkString(",")) ++
    approve(mcpTools ++ networkTools)
```

i.e. the tool exists (`--tools`) *and* is pre-approved (`--allowedTools`). Yet
the enforcement cell still answers `Hard` — "`--tools` confines the turn to the
read-only names" (`ClaudeArgs.scala:181-185`) — so no `EnforcementNotice`
fires, and `AgentBackend.enforcementCell`'s scaladoc invariant
(`tools/src/main/scala/orca/backend/AgentBackend.scala:110-117`: grants widen
the tools on offer "without changing how the tier itself is enforced") is
falsified.

`claude.withNetworkTools(Seq("Bash"))` is a plausible fallible-author mistake
("my planner needs `gh`; `gh` needs a shell") — and it hands a NetworkOnly
planner an auto-approved shell with no error, no notice, and a table still
claiming Hard. Every other route to widening a tier is either validated or
classified; this one is neither.

## Proposed solution

In `ClaudeBackend.withNetworkTools`, reject claude's write-capable builtin
names with the same `IllegalArgumentException` pattern as the existing shape
check. Define the denylist next to `ReadOnlyTools`/`DefaultNetworkTools` in the
claude module, e.g.:

```scala
/** Builtins that would hand a NetworkOnly turn a write primitive back;
  * withNetworkTools exists solely to add network reads. */
private[claude] val WriteCapableTools: Set[String] =
  Set("Bash", "Write", "Edit", "NotebookEdit")
```

(Confirm the exact current builtin names against the claude CLI's tool roster
before finalizing the set; include shell-adjacent names like `KillShell` /
`BashOutput` if the installed CLI ships them.) The error message must name the
next action per AGENTS.md convention — e.g. suggest `ToolSet.Full` if the
caller genuinely needs a shell.

Test: add to `claude/src/test/scala/orca/tools/claude/ClaudeBackendTest.scala`
one test asserting `withNetworkTools(Seq("Bash"))` throws with a message naming
the tool.

Must NOT change: the shape validation, the sibling-backend sharing of
`closedFlag`/`enforcementNotice` (both are load-bearing, see the scaladoc at
`ClaudeBackend.scala:85-91`), or `DefaultNetworkTools`.

## Verification

**Verdict: CONFIRMED.**

Checked ClaudeBackend.withNetworkTools at ClaudeBackend.scala:92-109 (only the `BareToolName.matches` shape check; `"Bash"` passes), ClaudeArgs.scala:134-136 (NetworkOnly puts `networkTools` on both `--tools` and `--allowedTools`), the Hard cell at :181-185, and the `AgentBackend.enforcementCell` scaladoc invariant at :110-117 — all exact. Framed as a fallible-author mistake, not adversarial — passes the threat model. Grepped `withNetworkTools` callers: production goes through `Agent.withNetworkTools` (tools Agent.scala:343); test doubles (`StubClaudeAgent`, `AgentCheapTest`, `DefaultFlowContextTest`) stub it as identity, unaffected. The throwing `IllegalArgumentException` matches the existing shape-check pattern; `ClaudeBackendTest.scala` exists. Implementable as written; no breakage found.
