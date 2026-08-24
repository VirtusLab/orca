# Minor duplication and comment cleanups in GitTool.scala

**Aspect**: conciseness  **Severity**: low

## Problem

Small repeated shapes and four comments that fail the project's locality
test, all in `tools/src/main/scala/orca/tools/GitTool.scala`:

1. **`Step` emission wrapper.** `events.onEvent(OrcaEvent.Step(...))`
   appears at 12 sites (540, 549, 569-573, 586, 592, 596, 653, 723-725,
   999-1001, 1016, 1029), three forced onto 3-5 lines by the wrapper's
   length.
2. **`commitOnly` repeats `commitStaged`'s tail.** Lines 589-592 duplicate
   594-596 (same `git commit -m … -- path`, same `"Committed: $message"`
   literal, also at 586) instead of calling it. (Superseded if finding 08's
   `forceCommitOnly` merge lands first — then `commitOnly` calls the shared
   private commit-pathspec helper.)
3. **`commit`'s clean check re-spells `isDirty()`.** Line 583
   (`git("status", "--porcelain").trim.isEmpty`) is the same
   porcelain-emptiness check as `isDirty` (555), plus a 2-line comment
   (581-582) restating the rationale that already lives on the porcelain
   family.
4. **Comments restating trait docs / the next line** (violating the
   AGENTS.md comment rules):
   - 992-994: "Check out existing branch if it already exists; otherwise
     branch off HEAD. `git branch --list <name>` prints the branch when it
     exists, empty when not." — first sentence restates the trait doc
     (483-485) and the `if branchExists` line below; second describes
     `branchExists`'s implementation from another function.
   - 1023-1024: "Best-effort: swallow all failures … Never attempt to
     delete the current branch." — verbatim restatement of the trait doc at
     505-509.
   - 1037-1039: "Two-dot diff (direct) to see all changes … minus the orca
     bookkeeping directory …" — restates the trait doc at 512-515 and the
     `wholeRepoExceptOrca` argument on the next line.
   - `OsGitToolTest.scala:472`: "The branch should no longer be listed." —
     restates the assertion that follows.

## Proposed solution

```scala
private def step(msg: String): Unit = events.onEvent(OrcaEvent.Step(msg))

def commitOnly(path: os.Path, message: String)(using WorkspaceWrite): Unit =
  val _ = git("add", "--", path.toString)
  commitStaged(path, message)   // or the finding-08 shared helper
```

In `commit`: `if !isDirty() then Left(new NothingToCommit)` (with `isDirty`
private per finding 03), dropping the comment at 581-582. Delete the four
comments listed in item 4.

Estimated net saving: ~20 lines. No behavior change anywhere; existing
tests pass unchanged.

Must NOT change: the `Step` message texts (asserted by
`OsGitToolTest.scala:268-289`), the staging-then-porcelain order inside
`commit`.

## Verification

**Verdict: CONFIRMED.**

Checked all 12 `Step` sites at the cited lines; commitOnly:589-592 duplicates commitStaged:594-596 exactly; commit:583's porcelain check is behaviorally `!isDirty()` (dirtyPaths parses the same porcelain; nonempty-lines is equivalent to trim-nonEmpty); all four cited comments exist verbatim and restate trait docs / the next line / the assertion. No behavior change; `Step` texts preserved.

Ordering: apply after findings 03, 05, and 08 — the `addWorktree` comment (992-994) and `diffBranchExcludingOrca` comment (1037-1039) become moot when 03/05 land, and item 2 is explicitly superseded by 08's `forceCommitOnly` merge.
