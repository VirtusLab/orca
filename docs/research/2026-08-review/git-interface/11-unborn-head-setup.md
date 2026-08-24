# Repo with no commits: setup dies with an opaque git error

**Aspect**: correctness  **Severity**: low

## Problem

On a fresh `git init`, `git rev-parse --abbrev-ref HEAD` exits 128
("fatal: ambiguous argument 'HEAD'"). `currentBranch()`
(`tools/src/main/scala/orca/tools/GitTool.scala:666-667`) therefore throws
— and it is the first git call in flow setup
(`runner/src/main/scala/orca/runner/FlowLifecycle.scala:308`). `diff()`,
`diffStat()`, `pendingChanges()` hard-reference `HEAD` and throw likewise.
Meanwhile `headCommit()` explicitly documents and handles the no-history
case (GitTool.scala:273-277), so partial support was intended.

"Run orca on a just-created project" is a plausible first use; it aborts
with `git rev-parse --abbrev-ref HEAD failed (exit 128): fatal: ambiguous
argument 'HEAD'…` instead of anything actionable. The project's own rule
("every user-facing refusal names the next action", AGENTS.md) is violated
by accident here.

## Proposed solution

Detect unborn HEAD at setup and refuse with a named next action: in
`FlowLifecycle.setup`, immediately before `git.currentBranch()`
(FlowLifecycle.scala:308), guard with the existing probe:

```scala
if git.headCommit().isEmpty then
  throw OrcaFlowException(
    "repository has no commits yet — make an initial commit before running a flow"
  )
```

Do NOT change `currentBranch()`'s implementation to
`git branch --show-current`: it prints empty in detached HEAD where
`rev-parse --abbrev-ref` prints the literal `"HEAD"`, which skip-branch's
detached refusal (FlowLifecycle.scala) and `RecoveryCheck` both compare
against.

Known, accepted imprecision: `headCommit()` also returns `None` when git
itself is unavailable, so a broken git install would get the "no commits
yet" message — rare, and every path after this point would fail on the same
broken git anyway.

Tests: `runner/src/test/scala/orca/runner/FlowLifecycleTest.scala` — setup
on `GitRepo.empty()` fails with the "no commits yet" message (the suite
currently covers only `headCommit` on an empty repo,
`tools/src/test/scala/orca/tools/OsGitToolTest.scala:~564-566`).

Must NOT change: `headCommit()`'s `Option` contract; detached-HEAD handling.

## Verification

**Verdict: CONFIRMED-REVISED.**

Checked GitTool.scala:666-667 and 273-277; FlowLifecycle.scala:305-308 (`currentBranch()` is the first *throwing* git call in setup — `warnIfSettingsIgnored`/`isIgnored` precedes it but is a swallowing probe; minor imprecision, core claim holds). Empirically reproduced: unborn HEAD → `rev-parse --abbrev-ref HEAD` exits 128 with the exact "ambiguous argument" fatal; `git branch --show-current` works on unborn HEAD but prints EMPTY in detached HEAD.

Revision rationale: the original's "optionally also switch `currentBranch()` to `git branch --show-current`" was a trap — both skip-branch's detached-HEAD refusal (`startBranch == "HEAD"`) and `RecoveryCheck`'s literal-`"HEAD"` handling depend on `rev-parse --abbrev-ref`'s literal `"HEAD"` output; applying the option as written would silently break detached-HEAD detection. The ## Proposed solution above is the revised version (guard only, option removed).
