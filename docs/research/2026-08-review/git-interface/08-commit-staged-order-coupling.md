# `commitStaged` encodes a call-order invariant only prose enforces

**Aspect**: complexity  **Severity**: medium

## Problem

`commitStaged`'s contract is "assuming the caller already staged it
(typically via [[forceAdd]])" (`tools/src/main/scala/orca/tools/GitTool.scala:241-248`).
Its single production caller is the adjacent pair
(`runner/src/main/scala/orca/runner/FlowLifecycle.scala:825-826`):

```scala
git.forceAdd(store.path)
git.commitStaged(store.path, "orca: progress log")
```

"Call `forceAdd` before `commitStaged`" is a temporal invariant no type
expresses; called alone, `commitStaged` throws at runtime ("nothing to
commit"). Three commit variants (`commit`, `commitOnly`, `commitStaged`)
also force a reader to diff three contracts to pick one, when
`commitStaged` exists only as the second half of this one pair.
(`forceAdd`'s other caller, `flow/src/main/scala/orca/Flow.scala:134`, is
followed by `commit()`'s `add -A`, so `forceAdd` must stay standalone.)

## Proposed solution

Replace `commitStaged` with a self-contained operation doing both steps:

```scala
/** Force-stage `path` (`git add -f`, punching through `.gitignore`) and
  * commit exactly it (commit pathspec — nothing else staged or dirty leaks
  * in). For files that must land even under a gitignored `.orca/`: the
  * progress-log header commit (ADR 0018 R8).
  */
def forceCommitOnly(path: os.Path, message: String)(using WorkspaceWrite): Unit
```

Implementation is the two existing bodies concatenated
(GitTool.scala:594-599). FlowLifecycle.scala:825-826 becomes one call; keep
`forceAdd` for Flow.scala:134. Move `commitStaged`'s existing rationale
comment (why a plain `add` refuses an ignored-but-staged path, hence `-f`)
onto the new method. Update the README `git` row.

Tests: replace `commitStaged`'s test in
`tools/src/test/scala/orca/tools/OsGitToolTest.scala` with one asserting
`forceCommitOnly` commits a gitignored path and leaves other dirty files
out.

Must NOT change: `commit`'s `add -A` contract, `commitOnly`'s
gitignore-respecting guard usage at FlowLifecycle.scala:843-847, the
`Step` event shape (`"Committed: $message"`).

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; one missed caller and two doc corrections).

Checked GitTool.scala:239-248 (trait doc), 594-596 (impl), FlowLifecycle.scala:825-826 (the adjacent pair, verbatim), Flow.scala:134+137 (`forceAdd` then `commit` — confirms `forceAdd` must stay standalone). Grep found one caller the solution missed.

Solution revision: also update `runner/src/test/scala/orca/runner/FlowLifecycleTest.scala:2406`, which calls `git.commitStaged(setup.store.path, "orca: progress log")` directly — port it to `forceCommitOnly` (it simulates the header commit, so the merged call is the faithful port). The doc comment at `runner/src/main/scala/orca/runner/FlowLifecycle.scala:749` says "`forceAdd` + `commitStaged`, never `add -A`" — reword to name `forceCommitOnly`. The README `git` row does not list `commitStaged`, so drop the README instruction.
