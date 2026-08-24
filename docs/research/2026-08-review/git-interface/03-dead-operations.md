# A third of the trait has no production caller — delete the dead operations

**Aspect**: complexity  **Severity**: high

## Problem

`GitTool` exposes 35 public operations
(`tools/src/main/scala/orca/tools/GitTool.scala:206-519`). The per-operation
usage map (appendix below; production = `src/main` of any module plus the
shipped `flows/*.sc`) shows these have zero production callers:

- **`add`** (GitTool.scala:262, impl 601-606) — only
  `OsGitToolTest.scala:77-92`. Its trait doc claims it exists "so the
  settings-file commit (ADR 0019) never punches a `.orca/`-ignored file into
  history" — but the actual settings commit guards with `isIgnored` and calls
  `commitOnly` (`runner/src/main/scala/orca/runner/FlowLifecycle.scala:843-847`).
  Dead code with a doc naming a consumer that doesn't use it.
- **The worktree suite**: `addWorktree`/`removeWorktree`/`listWorktrees`
  (GitTool.scala:487-503, impl 987-1020) plus dedicated machinery —
  `Worktree` (28), `WorktreeAddFailed` (193-194), `WorktreeNotFound`
  (199-200), `parseWorktreeList` (1299-1313), `isWorktreeAlreadyPresent`
  (1195-1196), `samePath` (1043-1047), `WorktreePrefix`/`BranchPrefix`
  (1260-1261). Only callers: `OsGitToolTest.scala:209-312` (~90 lines) and
  `CliFailurePredicatesTest`. No flow (shipped or example) uses worktrees;
  the trait doc's "Lets a flow work on several tasks in parallel" describes
  a capability nothing exercises. ~140 lines of main code (~11% of the file).
- **`log` + `CommitInfo`** (GitTool.scala:11, 430, impl 897-910) — tests only
  (`OsGitToolTest.scala:192-202`, `FlowLifecycleTest.scala:1979`); README
  line 302 uses `git.log` merely as an example of a pure read.
- **`isDirty`** (GitTool.scala:468-471) — production-unused; internally
  `ensureClean` uses it; the one external use is
  `FlowLifecycleTest.scala:2143` (replaceable with `dirtyPaths().isEmpty`).

Companion-object leftovers with too-wide visibility:
`parseWorktreeList` and `wholeRepoExceptOrca` (GitTool.scala:1114) are
public with no caller outside the file; `remoteHost` (1216) is
`private[tools]` but consumed only by `isGithubRemote` in the same object.

Every unused operation still costs contract doc, event wiring, and tests,
and widens what a reader must understand to answer "what can mutate the
repo here". Orca is 0.x and its script consumers are version-pinned, so
no compatibility is owed.

## Proposed solution

In `tools/src/main/scala/orca/tools/GitTool.scala`:

- Delete `add` (trait + impl + its three-line ignored-path comment) and the
  test at `OsGitToolTest.scala:77-92`. The ADR 0019 guarantee already lives
  at FlowLifecycle.scala:843.
- Delete the three worktree operations and all eight supporting
  declarations listed above; delete `OsGitToolTest.scala:209-312` and the
  worktree cases in `CliFailurePredicatesTest`. Re-adding later with a real
  caller shaping the API is cheap.
- Delete `log` and `CommitInfo`; update `FlowLifecycleTest.scala:1979` to
  read the log via `os.proc` (as other lifecycle tests already do) and drop
  `OsGitToolTest.scala:192-202`. Replace the README:302 example with another
  pure read (`git.changedFiles`).
- Drop `isDirty` from the trait; keep the porcelain-emptiness check private
  in `OsGitTool` (`ensureClean` and `commit` use it — see finding 13).
- Tighten `wholeRepoExceptOrca` and `remoteHost` to `private`.
- Update the README `git` row (README.md:181): remove
  `add`/`log`/`addWorktree`/`removeWorktree`/`listWorktrees`/`isDirty` and
  the `WorktreeAddFailed`/`WorktreeNotFound` error mentions.

Estimated saving: ~180-200 lines of GitTool.scala plus ~110 test lines.

On the Scala-3-types question (owner's driving question 4): no new opaque
types are warranted. The validated branch type already exists one layer up
(`flow/src/main/scala/orca/progress/FeatureBranch.scala:71` — "Unwrap for
the git layer — call at the `GitTool` call site") and every mutating branch
call site funnels through it; `since: Option[String]`'s only producer is
`headCommit()` passed through verbatim. The typed wins here are removals:
`DiffMode` (finding 09) and `ensureClean`'s unused Boolean (finding 12).
`ShowDetail` and `GitReadFailed` are already well-typed and earn their place
at the MCP boundary.

Must NOT change: `commitOnly`/`forceAdd` (live), `dirtyPaths` (live at
FlowLifecycle.scala:471), the `show`/`fileAt` surface, and the
`private[tools]` test seams that have tests (`pushArgs`,
`gitFailureMessage`, `isNonFastForward`, `isRemoteDeclined`,
`isGithubRemote`, `parseNumstat`, `nonInteractiveEnv` — the last also used
by `GitHubTool.scala:349`).

## Appendix: usage map (production call sites)

| Operation | Production call sites | Verdict |
|---|---|---|
| createBranch | FlowLifecycle.scala:931, 943 | keep |
| checkout | FlowLifecycle.scala:1045, 1047 | keep |
| commit | Flow.scala:137; FlowLifecycle.scala:1001 | keep |
| commitOnly | FlowLifecycle.scala:844 | keep |
| commitStaged | FlowLifecycle.scala:826 | merge (finding 08) |
| forceAdd | Flow.scala:134; FlowLifecycle.scala:825 | keep |
| add | — | **delete** |
| push | openPrFromBranch.scala:34; FlowLifecycle.scala:1008; flows/issue-pr-bugfix.sc:121,141 | keep |
| currentBranch | FlowLifecycle.scala:308, 576 | keep |
| headCommit | Flow.scala:48 | keep |
| isIgnored | FlowLifecycle.scala:193, 843 | keep |
| defaultBranch | FlowLifecycle.scala:340 | keep (finding 07) |
| upstreamHas | FlowLifecycle.scala:1008 | keep |
| resetHard | FlowLifecycle.scala:1056 | keep |
| diff | script surface only (README, FlowCompilesTest.scala:212) | keep as script API (finding 12) |
| diffStat | internal (pendingChanges) | privatize (finding 04) |
| untrackedPaths | internal (composites) | privatize (finding 04) |
| reviewDiff | — (ReviewLoop.scala:327 is a comment) | drop (finding 04) |
| changedFiles | ReviewDiffSource.scala:44 | keep |
| changedFileStats | internal (changedFiles) | drop (finding 04) |
| reviewChanges | ReviewDiffSource.scala:38 | keep |
| pendingChanges | Flow.scala:161 | keep |
| diffVsBase | openPrFromBranch.scala:39; flows/issue-pr-bugfix.sc:168 | keep (findings 02, 09) |
| defaultBase | openPrFromBranch.scala:39; flows/issue-pr-bugfix.sc:168 | keep (finding 07) |
| log | — | **delete** |
| show / fileAt | RepoMcpServer.scala:71, 78 | keep |
| ensureClean | FlowLifecycle.scala:469, 479 | keep (finding 12) |
| isDirty | — | **delete** |
| dirtyPaths | FlowLifecycle.scala:471 | keep |
| addWorktree / removeWorktree / listWorktrees | — | **delete** |
| deleteBranch | FlowLifecycle.scala:1046 | keep |
| diffBranchExcludingOrca | FlowLifecycle.scala:1039 | keep, reshape (finding 05) |

## Verification

**Verdict: CONFIRMED-REVISED** (dead-code claims all verified; four corrections to the solution's test edits).

Independently re-grepped every claimed-dead symbol across all modules, tests, flows/, examples/, README, and adr/: zero production callers for `add`, the worktree suite + all eight support declarations, `log`/`CommitInfo`, and `isDirty` — confirmed. The `add` trait doc's ADR 0019 claim is false as described (the actual settings commit guards with `isIgnored` + `commitOnly`, FlowLifecycle.scala:840-847). The 35-operation count is exact, and the appendix caller map is accurate against independent greps. Visibility claims on `wholeRepoExceptOrca`/`remoteHost` verified.

Solution revision — four corrections:
1. Wrong test-deletion range: "delete OsGitToolTest.scala:209-312" would delete the ensureClean tests (245-256), the Step-event test (~258-289), and the NothingToCommit test (~291-295), which must stay. Correct: delete the worktree tests at ~204-242 and ~297-312 only.
2. Missed `log` caller: OsGitToolTest.scala:118 (`val entries = git.log(1)` inside "commit stages all changes and records the message") — port to `os.proc("git", "log", "-1", "--format=%s")` like FlowLifecycleTest:1979.
3. Missed `isDirty` caller update: FlowLifecycleTest.scala:2143 — change to `git.dirtyPaths().isEmpty`.
4. README row correction: the row at README.md:181 does not list `add` or `isDirty`; only `log`, `addWorktree`/`removeWorktree`/`listWorktrees`, and the `WorktreeAddFailed`/`WorktreeNotFound` error mentions need removing.

Note: finding 05's probe implementation uses `wholeRepoExceptOrca` from within OsGitTool's file, so tightening it to `private` remains valid.
