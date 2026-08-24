# A dash-leading branch name crashes opaquely instead of surfacing the typed error

**Aspect**: correctness  **Severity**: low

## Problem

`branchExists` (`tools/src/main/scala/orca/tools/GitTool.scala:552-553`)
passes the name right after `--list`; `git branch --list -x` exits 129
("unknown switch", verified empirically). So `createBranch("-x")` /
`checkout("-x")` throw a raw `OrcaFlowException` with git's usage text
instead of returning their documented typed `Left`. `deleteBranch` swallows
the same failure (GitTool.scala:1027); the worktree path has the same shape
if it survives finding 03.

The runner path is safe — names are slugged
(`BranchNamingStrategy.slug`) — but flow scripts call
`git.createBranch(...)` directly (`flow/src/main/scala/orca/accessors.scala`),
so a script-computed name (say, derived from an issue title without
slugging) with a leading dash is a plausible accident. The consequence is a
confusing crash rather than a wrong action (git rejects the flag), but the
typed-error contract (`BranchAlreadyExists`/`BranchNotFound`) that callers
pattern-match on is silently bypassed. Rev validation exists
(`GitRead.rev` + `--end-of-options`) but is applied only to `show`/`fileAt`.

## Proposed solution

One-line fix in `OsGitTool.branchExists` (GitTool.scala:553): pass the name
after `--`:

```scala
private def branchExists(name: String): Boolean =
  git("branch", "--list", "--", name).trim.nonEmpty
```

This restores both documented behaviors without touching any signature
(verified against git 2.53.0):

- `checkout("-x")`: `branchExists` now returns false, so the caller gets the
  documented `Left(BranchNotFound("-x"))` — typed, and semantically true.
- `createBranch("-x")`: `branchExists` returns false, then `git checkout -b -x`
  fails and the existing `fail` path throws
  `OrcaFlowException("git checkout -b -x failed (exit 128): fatal: '-x' is not a valid branch name")`
  — git's own message names the problem, no usage spam.

Do NOT widen the `Left` types with a new `InvalidBranchName`:
`FlowLifecycle.freshRun` matches `case Left(_) =>` on `createBranch` as
"name collision, try the fallback name", so a widened error would be silently
misrouted there.

Tests (`tools/src/test/scala/orca/tools/OsGitToolTest.scala`):
`checkout("-x")` returns `Left(BranchNotFound)`; `createBranch("-x")` throws
an `OrcaFlowException` whose message contains "not a valid branch name"
(never "unknown switch"); existing createBranch/checkout tests stay green
(pins that real names still match after `--`).

Must NOT change: `BranchNamingStrategy.slug`, the `Either` shapes callers
match on, `deleteBranch`'s swallow-all teardown contract.

## Verification

**Verdict: CONFIRMED-REVISED.**

Checked GitTool.scala:552-553 and 1022-1030; empirically reproduced `git branch --list -x` → exit 129 + usage text, so `createBranch("-x")`/`checkout("-x")` throw raw `OrcaFlowException` with usage spam instead of their typed `Left`. Also established empirically what the original didn't check: `git branch --list -- -x` exits 0 (empty; real branch names still found after `--`), and `git checkout -b -x` fails with git's own clear `fatal: '-x' is not a valid branch name`.

Revision rationale: the original offered three loosely-specified alternatives, one of which (widening the `Left` types with `InvalidBranchName`) would actively misroute in production — `FlowLifecycle.freshRun` (runner FlowLifecycle.scala:~931) matches `case Left(_) =>` on `createBranch` and treats it as a name collision, silently retrying an invalid-name error under the fallback name. The one-line `--` fix above restores the whole contract; the ## Proposed solution above is the revised version.
