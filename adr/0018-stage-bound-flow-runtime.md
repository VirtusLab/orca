# 0018. Stage-bound flow runtime: resumable stages, capabilities, progress log

Status: Accepted · Date: 2026-06-23
Supersedes: [ADR 0013](0013-persistent-plans.md) (persistent plans)
Related: [ADR 0002](0002-context-function-flow-dsl.md) (flow DSL / `FlowContext`),
[ADR 0003](0003-pluggable-llm-backends.md) (backend SPI),
[ADR 0016](0016-toolset-capability-axis-and-planner-network.md) (capability axis)

A `flow(...)` run is bound to a branch and a progress log, and is composed of
**stages**: named, resumable, committing units of work. A stage records its result,
commits its changes, and is skipped on a re-run if already complete — so a flow that
fails partway through resumes from the first incomplete stage rather than repeating
finished work. This generalises the per-task persistence the example flows hand-roll
today (`Plan.recover` + `implementTaskLoop`) up to the level of every side-effecting
step. This ADR is the complete design record; the only code below is illustrative
*flow scripts* and signatures (no implementation yet).

## 1. Context

Flows are long-running: a single run churns over a task list for many minutes to
hours, with agent turns, reviews, and CI waits throughout. At that duration,
interruption is the norm, not the exception — a dropped network connection, a killed
process, a hit rate limit, a machine sleeping. The goal is that **interrupting a flow
mid-run costs at most the in-progress stage**: everything already finished is recorded
and committed, and re-running the same command picks up from the first incomplete
stage rather than re-planning, re-implementing, and re-paying for work that already
landed. Restartability is the feature; stages are the unit at which it is guaranteed.

Today only *multi-task* flows resume, via a bespoke mechanism (ADR 0013):
`Plan.recover` + `implementTaskLoop` + a committed `.orca/plan-<hash>.md` whose
checkboxes track progress. Non-task phases (triage, failing-test, CI-wait,
repro-verification) are not resumable — `issue-pr-bugfix.sc` uses the in-memory task
loop precisely because those phases "aren't restartable from a plan file alone." The
resume logic is hand-rolled per flow.

Backward compatibility is explicitly **not** a goal. Orca is early and has very few
users, so this design is free to break existing tool signatures and flow scripts
where that yields a cleaner result (the `InStage` change in particular touches every
flow).

---

## 2. Requirements & design (the decision)

Each subsection lists the requirements it must meet — the **R**n labels are stable
identifiers referenced elsewhere in this document, grouped by topic rather than
ordered numerically — followed by the design that meets them. (Where a requirement and
its mechanism say the same thing the requirement is kept terse and the detail lives in
the design.)

**Cross-cutting requirements** (hold throughout):
- **R27** — The API is type-safe: the capabilities (`FlowContext`, `FlowControl`,
  `InStage`, `WorkspaceWrite`), stage results (`JsonData`), and session ids (`SessionId[B]`,
  parameterised by backend) are checked at compile time; the runtime never relies on
  stringly-typed or untyped state where a type would do.
- **R28** — The on-disk serialisation format is JSON throughout (the progress log,
  stage results, the header), via jsoniter codecs.

### 2.1 Stages

**Requirements.**
- **R7** — The stage is the unit of work: a named region that, on success, records
  its return value in the progress log.
- **R8** — Every stage commits on completion (progress-log delta + code changes) as
  one commit. orca force-adds its progress file (`git add -f` of the single
  progress-log path — never a glob or directory, so nothing else gitignored is swept
  in), so it is committed even when the project gitignores `.orca/`. Projects are in
  fact encouraged to gitignore `.orca/` — that keeps orca's scratch state out of `git
  status`, while the force-add still tracks the one file that must travel with the
  branch. The commit is therefore never empty and the log stays on the branch.
- **R10** — A stage's id is a hierarchical path of `name#occurrenceIndex`
  segments, one per enclosing stage, joined by `/` (e.g. `outer#0/inner#0`); the
  occurrence index counts prior same-named stages *within the same parent frame*,
  never a global execution counter. Nesting scopes the counter, so a nested
  stage's id can never collide with a top-level stage of the same name.
- **R11** — On resume, a stage whose recorded result decodes to its call-site type
  is skipped (stored value returned); a result that fails to decode (the stage's
  type changed) re-runs the stage — fail-safe over silent misattribution.
- **R12** — Stages may nest on one thread (each nested stage commits) but must not
  run concurrently — two stages committing at once would corrupt the shared git
  index. This is structural: starting a stage needs `FlowControl` (R29), and
  concurrency combinators hand forks only the thread-safe `FlowContext`, never
  `FlowControl`, so a fork (e.g. a parallel reviewer) cannot start a stage. Lexically
  capturing an outer `FlowControl` into a fork was a convention violation when this
  ADR was accepted; separation checking now makes it a compile error at the checked
  fork funnel (§6).
- **R13** — The commit message defaults to an `agent.cheap` summary of the diff; a
  stage may pass `commitMessage: Option[T => String]` to derive it from its result.
- **R14** — `display(...)` gives progress-only output: no stage, id, commit, or log
  entry.

**Design.**

```scala
def stage[T: JsonData](name: String, commitMessage: Option[T => String] = None)
    (body: (InStage, WorkspaceWrite) ?=> T)(using FlowControl): T
def display(message: String)(using FlowContext): Unit
def fail(message: String)(using FlowContext): Nothing
```

A `stage` runs `body` with `InStage` and `WorkspaceWrite` evidence in scope, then
records and commits:

1. **Resume check.** Compute the id. If the log holds a completed entry for it,
   decode the stored JSON with `JsonData[T].codec` and return it without running
   `body`. If decoding fails (the script changed the stage's result type under this
   id), run `body` instead — fail-safe over feeding back a wrong value.
2. **Run.** `body` executes; its LLM calls consume the supplied `InStage`, its
   workspace writes the supplied `WorkspaceWrite`.
3. **Record & commit.** Append a `StageEntry` (id, name, result JSON), force-add the
   log, then make one commit covering the log delta plus any code changes. The
   message is `commitMessage(result)` if the stage supplied one, otherwise an
   `agent.cheap` summary of the changed files. The log always changes, so the commit
   is never empty.

`display` shows a progress line without checkpointing; `fail` emits an error and
throws, unwinding to the failure teardown (§2.5). Both need only `FlowContext`, so
they're callable anywhere — outside a stage, or inside a fork.

Resume is uniform across stage kinds: an interactive stage (a planning
conversation the user drove) is skipped on resume just like any other, returning
its stored result — the user is not re-prompted for work already done.

**Id.** A stage's id is a hierarchical *path*: the `name#occurrenceIndex` segments
of its enclosing stages joined by `/`, ending in its own (e.g. a top-level stage is
`plan#0`; a stage named `inner` nested inside `outer#0` is `outer#0/inner#0`). The
occurrence index counts prior same-named stages *within the same parent frame* this
run — each open stage carries its own per-name counter, and the flow body is the
root frame. Because the key is the name (scoped by nesting) rather than an
execution-order counter, inserting, removing, or reordering *other* stages between
runs does not shift a stage's id: a removed stage leaves a harmless orphan entry; a
newly inserted stage simply has no recorded result and runs. A stage whose dynamic
name changes between runs (e.g. a task title from a regenerated plan) re-runs —
fail-safe.

The path structure is load-bearing for resume correctness under nesting. The id is
computed once, before the resume decision, by opening the stage's frame against its
*parent* — this bumps the parent's occurrence slot exactly once whether the stage
runs or is skipped (so later same-named siblings stay stable), and the frame is
popped in a `finally`. A skipped (resumed) stage's body never runs, so its nested
stages never open their own frames: their ids are structurally unreachable and no
counter desyncs. A flat, un-nested id scheme could not offer this — a skipped
parent's vanished nested bumps would let a later same-named stage recompute the
nested stage's id and either silently replay its stale value (same result type) or
clobber its record on re-run (different type). The `#`/`/` id is opaque: it is only
ever compared for exact equality, never parsed.

**Nesting.** Nested stages each commit, so nest only to introduce an extra
checkpoint; wrapping a stage solely around other stages yields a commit carrying just
that stage's progress entry. Why two stages can't run concurrently — the
`FlowControl`-vs-`FlowContext` capability split — is in §2.2 (R12).

> **Amendment (2026-07-06).** A nested stage's commit stages the whole tree, so
> it sweeps up any uncommitted edits the outer stage's body made before the
> nesting point — resume is only correct if the outer body is idempotent over
> its own leftovers. See the `stage` scaladoc in stage.scala for the full
> rationale.

> **Amendment (2026-08-02).** A stage also records the commit HEAD pointed at
> when it began, alongside its id (`enterStage(name, baseCommit)`, read back as
> `FlowControl.stageBaseCommit`). Nothing here forbids a stage's agent from
> committing its own work, so `git diff HEAD` does not answer "what has this
> stage produced" — the recorded baseline does, and it must be read at
> `enterStage` because by the time anything asks, HEAD may have moved. `None`
> when there was no commit to record. `reviewAndFixLoop` is the consumer (ADR
> 0011 amendment 2026-08-02).
>
> Known limitation, inherited rather than introduced: in a repository with no
> commits at all there is no baseline AND `git diff HEAD` itself fails, so a
> review in that state aborts the stage. It predates this amendment and no flow
> reaches it — setup commits the progress header before any user stage runs.

### 2.2 Capability gating

**Requirements.**
- **R15** — Every side-effecting operation requires stage-bound evidence, which
  only the runtime can construct and which a stage body receives as context
  parameters: file writes, git mutations, and GitHub mutations require
  `WorkspaceWrite`; **all** LLM calls require `InStage`. (Originally one combined
  `InStage` token; split 2026-07-07 for capture checking — §6.)
- **R16** — Pure reads (`fs.read`, `git.uncommittedDiff` / `log` /
  `head`, `gh.readIssue` / `buildStatus` / `waitForBuild`) require
  neither token and are callable outside stages.
- **R17** — Library helpers that perform side effects take the matching token
  clause — `(using InStage)` for LLM calls, `(using WorkspaceWrite)` for workspace
  writes, both if they do both — and run under the caller's stage rather than
  opening their own committing stages, so a task still yields a single commit. A
  helper that itself *starts* stages instead declares `using FlowControl` (R29),
  making that visible in its signature.
- **R29** — Starting a stage requires a `FlowControl` capability, where
  `FlowControl <: FlowContext`: everything a `FlowContext` is (reads, `agent`, `emit`)
  plus the authority to open a stage — but **thread-affine**, never handed to a fork.
  `flow` provides it; `stage` requires it. At a direct `stage(...)` in a flow body it
  resolves implicitly (zero ceremony); a stage-starting *helper* spells out
  `using FlowControl`, so the fact is visible in its type.

**Design.**

```scala
trait FlowContext                       // thread-safe, shareable: reads + agent + emit
trait FlowControl extends FlowContext,  // + authority to start stages; thread-affine,
      caps.ExclusiveCapability          //   fork-opaque under separation checking
final class InStage                     // in-stage LLM-call token, from `stage(...)`;
      extends caps.SharedCapability     //   fork-capturable
final class WorkspaceWrite              // in-stage workspace-write token, from `stage(...)`;
      extends caps.ExclusiveCapability  //   fork-opaque
```

Four capabilities, all constructible only inside `orca`:

- **`FlowContext`** — the narrow, thread-safe context (tool reads, `agent`,
  `userPrompt`, `emit`/`display`). Safe to share into parallel forks, so concurrent
  reviewers each use it.
- **`FlowControl`** — a *subtype* of `FlowContext` adding the authority to start a
  stage. Subtyping (not a derived given) is the point: a `FlowControl` satisfies any
  `using FlowContext`, and the **downgrade is a one-way upcast** — concurrency
  combinators run each fork with only the `FlowContext` (`val ctx: FlowContext =
  control`), so `stage` (which needs `FlowControl`) cannot be called in a fork.
  This was convention pre-capture-checking (a fork could lexically capture an outer
  `FlowControl`); separation checking now makes that capture a compile error at the
  checked fork funnel (`FlowControl` is an exclusive capability — §6).
- **`InStage`** — the in-stage LLM-call token (R15), handed to a stage body by
  `stage`, in the spirit of Ox's `using Ox`. Shared (`caps.SharedCapability`):
  safe to capture into parallel forks — the reviewer fan-out depends on it.
- **`WorkspaceWrite`** — the in-stage workspace-write token (R15), handed to a
  stage body alongside `InStage`. Exclusive (`caps.ExclusiveCapability`):
  separation checking forbids capturing it into concurrent forks — two forks
  racing on the git index or progress log is exactly the bug this catches (§6).

Every side-effecting tool method gains a token clause. The methods gated:

- `GitTool`: `createBranch`, `checkout*`, `commit`, `push`, `ensureClean` —
  `(using WorkspaceWrite)`.
- `FsTool`: `write` — `(using WorkspaceWrite)`.
- `GitHubTool`: `createPr`, `updatePr`, `writeComment`, `upsertComment` *(new)* —
  `(using WorkspaceWrite)`.
- Every `Agent` / `AgentCall` / `AutonomousTextCall` entry point — `(using
  InStage)` (LLM calls are side-effecting: cost and non-determinism).

Side-effecting library helpers (`reviewAndFixLoop`, `lint`, `summarisePr`, `Plan`
generation, the reviewer fan-out) take `(using InStage)` and run under the
caller's stage, so the compiler enforces "no mutation outside a stage" while a
whole task still produces one commit. A helper that also writes the workspace
takes `(using WorkspaceWrite)` too (rare — e.g. the lifecycle's `freshRun`,
which both names the branch via the cheap model and performs the setup git
writes). The runtime's own `recordAndCommit` is different: it is a mint site,
not a token-receiving helper — it takes `(using FlowControl)` and mints fresh
tokens through the `RuntimeInStage` door.

Both tokens and `FlowControl` prove *lexical* enclosure. Since the Epic 0
capture-checking work (§6), the fork half is a hard guarantee: separation
checking rejects a fork thunk capturing an exclusive token (`WorkspaceWrite`,
`FlowControl`) at the checked fork funnel, while shared `InStage` passes. Stage
bodies are not typed pure, so a token smuggled *past* a stage (rather than into
a fork) remains convention — the residual §5 leak. Enforcement scope, the
verified Scala 3.8.4 facts, and the raw-`ox.fork` escape hatch are recorded in
§6's amendment.

### 2.3 Stage results

**Requirements.**
- **R9** — A stage's return value must be JSON-serialisable, witnessed by `JsonData`
  (the existing tapir-`Schema` + jsoniter-codec bundle). Its codec must round-trip
  losslessly (a resumed value equals the original). A return type without a
  `JsonData` instance is a compile error.

**Design.**

Stage results reuse the existing `JsonData[A]` (tapir `Schema` + jsoniter codec)
rather than a dedicated typeclass — `stage[T: JsonData]`. Every type that already
travels through LLM calls (`Plan`/`PlanLike`, `Issue`, `PrHandle`, `OpenFindings`,
…) is therefore a valid stage result with no extra work, and `derives JsonData` on a
new case class is enough. The persistence path uses only the codec; the `Schema`
rides along unused — a fair price for not maintaining a second typeclass.

The library adds `JsonData` givens for the handful of non-case-class results flows
return — primitives, `Unit`, `Option`, `List`, small tuples (`JsonData.derived` only
covers `Mirror` types). Deliberately absent: the opaque `SessionId[B]` and the
`FlowSession[B]` that wraps it (§2.6, R22) — a session is a live handle, not a value
to persist as a stage result, so neither has a `JsonData` given. A return type with no `JsonData` instance — that
live-handle case, or a closure — fails to compile, the intended boundary. Codecs must
round-trip losslessly: a resumed run reads the value back from JSON, so a lossy codec
would diverge from a fresh run. A human-readable summary for the log and `display`
reuses the existing `Announce` typeclass where one is in scope.

### 2.4 Progress log, store, and recovery

**Requirements.**
- **R18** — The progress log lives at a prompt-deterministic, branch-independent
  path (`.orca/progress-<hash>.json`) so recovery can locate and read it before
  checking out the feature branch.
- **R19** — The header records the starting branch, the feature-branch name, and the
  prompt hash, and is committed as the branch's first commit.
- **R20** — Recovery reads the header from the working tree using a
  snapshot-before-stash sequence (so an uncommitted/untracked log survives the
  start-of-flow stash), then checks out the recorded branch and replays.
- **R21** — The branch-naming strategy and the progress store (its path and format,
  via the `ProgressStore` below) are pluggable via flow configuration, with the
  defaults below. (Commit messages plug in per stage — R13; the log format is JSON —
  R28.)

**Design.**

```scala
case class ProgressHeader(startingBranch: String, branch: String, promptHash: String)
case class StageEntry(id: String, name: String, resultJson: String)
case class ProgressLog(header: ProgressHeader, entries: List[StageEntry])

trait ProgressStore:
  def load(): Option[ProgressLog]
  def writeHeader(header: ProgressHeader)(using WorkspaceWrite): Unit
  def upsertEntry(entry: StageEntry)(using WorkspaceWrite): Unit   // by id, last write wins

object ProgressStore:
  def default: ProgressStore   // JSON at .orca/progress-<hash>.json (the seam for R21)
```

The log is heterogeneous — successive entries hold different result types — so a
`StageEntry` is type-erased at rest (`resultJson`); a typed `StageEntry[T]` can't
live in one `List`. Type-safety is recovered at the *decode* boundary: `stage[T]`
decodes with `JsonData[T].codec`, and a decode failure re-runs the stage (R11), so a
wrong-typed entry never reaches the call site.

The default store serialises to `.orca/progress-<hash>.json`, where `hash` is the
first 12 hex chars of `SHA-256(userPrompt)` (the same scheme `Plan.defaultPath` uses
today). The path is independent of the branch, so recovery finds it without first
knowing which branch the work is on. `upsertEntry` upserts by id: a fail-safe re-run
overwrites the stale entry rather than leaving two under one id. A wholly malformed
or truncated log (e.g. a crash mid-write) is treated as *no log* — the run starts
fresh — rather than crashing recovery.

Recovery mirrors the proven `Plan.recover` sequence: snapshot the log file,
`git.ensureClean` (stash any pending edits — recoverable via `git stash pop`),
restore the log from the snapshot if the stash removed it, read the header,
**validate it as untrusted input (R32)**, `checkout` the recorded branch, and
replay. Because the branch name lives in the header, a non-deterministic branch name
is read back rather than recomputed.

The runtime also cross-checks the header against git state (R30): if the log
surfaces on a branch it does *not* name (e.g. an in-progress branch was merged,
carrying the log along), it aborts with a clear message rather than resuming against
the wrong branch.

### 2.5 Flow lifecycle & config

**Requirements.**
- **R1** — Each `flow(...)` run is bound to exactly one feature branch and one
  progress log.

  > **Amendment (2026-08-01).** R1 now holds across runs too. A FRESH run (its
  > own prompt-keyed log absent or corrupt) refuses to start when any OTHER
  > `.orca/progress-*.json` header names the branch it would run on: such a log
  > is by definition an unfinished run on that branch (failure teardown keeps
  > the log and stays put, success teardown deletes it — R5). Logs are
  > prompt-keyed (R18), so without this a differently worded task starts fresh
  > on top of the interrupted run's half-done branch. The refusal
  > (`OrcaFlowException`) names the branch, the interrupted run's recorded task
  > and flow — both sanitized, since the header is committed, hand-editable
  > content — and the log's path, then points at the three ways out: resume
  > that run, abandon it by deleting the log, or switch branches. It
  > runs before the stash (R4) so an abort leaves the tree untouched, applies
  > in skip-branch mode too, and never fires on a resume of the same prompt.
  > Corrupt logs and logs naming another branch are ignored; with several
  > matches the newest by mtime is reported.
- **R2** — The feature branch is created up-front, before the body runs, from the
  current HEAD, via a pluggable `BranchNamingStrategy`. Built-ins cover the common
  cases **safely**: **deterministic** ones derive a git-ref-safe name from structured
  input (e.g. `issue(handle)` → `fix/issue-42`) or slug arbitrary text (`fromText`),
  and a **prompt-shortening** one condenses a free-form prompt via the leading
  model's cheap variant (`agent.cheap`, R31) then slugs it. **All** free text — author
  titles and untrusted LLM output alike — routes through `slug`, which strips
  leading/trailing hyphens, forces a leading alphanumeric, caps length, and falls
  back to `flow-<shorthash>` on empty: so a branch name can never begin with `-`
  (CLI-flag/argument injection into `git`/`gh`) nor be empty. A non-deterministic name is
  computed once and recorded, never recomputed on resume; a deterministic name needs
  no read-back.

  > **Amendment (2026-07-25).** `OrcaArgs.skipBranch` (`--skip-branch`) opts
  > out of R2 entirely: the run binds to the CURRENT branch verbatim instead
  > of minting one — for a harness handoff where the user already planned
  > work on a branch that carries plan files, and orca should continue there
  > rather than fork off a fresh one. Refused (`OrcaFlowException`) when the
  > current branch is protected (`main`/`master`/the repo's detected
  > default) or detached HEAD (`git.currentBranch()` reads back the literal
  > `"HEAD"`, which would otherwise commit into an unnamed, GC-eligible
  > state) — the user must check out a feature branch themselves first. A
  > dirty tree is tolerated, not refused, on a FRESH skip-branch run (no
  > resumable progress log yet): unlike R4, no auto-stash — the leftover
  > files are likely the very hand-off context, so they stay in the working
  > tree for the flow/agent, swept into the first stage's commit; one
  > informational `Step` names the file count. Resuming an existing progress
  > log still auto-stashes exactly as R4 does, since an interrupted stage's
  > uncommitted partial work must not leak into the stage that re-runs. A
  > reused branch name is
  > validated against a weaker git-ref-safety check
  > (`RecoveryCheck.isSafeGitRef`: no slug shape, but still rejects
  > `-`-prefixes, whitespace/control chars, glob metacharacters, `..`, a
  > `.lock` suffix, and the literal `"HEAD"`) rather than R2's minted-name
  > slug shape, since it was never orca-authored (may be mixed-case,
  > `feature/JIRA-123`, …); `RecoveryCheck.validateHeader` accepts a
  > non-slug `header.branch` on resume under the same check, cross-checked
  > against the actual current branch (R30). Teardown (R5)'s throwaway
  > auto-delete is gated on a new header field, `branchCreated` (`true` for
  > every pre-existing header — the default a missing field decodes to —
  > since skip mode is the only case that ever sets it `false`): a branch
  > orca did not create is never deleted, even if a tampered header's
  > `startingBranch` (never cross-checked, unlike `branch`) is crafted to
  > name an existing branch that happens to diff-blank against the feature
  > branch. Residual, accepted: `branchCreated = false` blocks the *delete*
  > but not a `returnToStartBranch` *checkout* of a tampered
  > `startingBranch` — navigation only, never destructive. A PR flow's
  > branch-vs-base diff then includes the user's own prior commits on that
  > branch, not just orca's — intended, since the whole point is continuing
  > their work in place.
- **R3** — The starting branch is recorded. On a **successful** exit HEAD stays on
  the feature branch by default (so the author ends on the work — the common case
  is a flow that opens no PR); a flow that opens a PR passes `returnToStartBranch =
  true` to switch back to the starting branch afterward. (A throwaway branch is the
  exception — see R5 — where HEAD always returns to the starting branch.) On
  **failure** the flow stays on the feature branch, so a re-run resumes in place —
  HEAD is already on the right branch and the committed log is in the working tree.

  > **Amendment (2026-09-11).** The flow no longer asks: `returnToStartBranch`
  > is gone from `flow`/`runFlow`, and the handoff is derived per run
  > (`BranchHandoff.of`) from the run target, the worktree, and whether the run
  > opened a PR (recorded through `orca.pr.recordOpenedPr`). HEAD returns to the
  > starting branch only for a `RunTarget.NewBranch` run, outside a worktree,
  > that opened one; `--skip-branch` and `--worktree` never move the checkout.
  > The throwaway-branch exception (R5) is unchanged.
- **R4** — On flow start a dirty working tree is stashed, with a user-visible
  warning, so the flow begins clean.

  > **Amendment (2026-08-25).** `OrcaArgs.keepChanges` (`--keep-changes`) opts
  > out of the stash on a FRESH run in BOTH branch modes — same tolerance, same
  > one-`Step` notice as the R2 amendment above; in normal mode the kept files
  > survive branch creation and reach the new branch through the first stage's
  > commit. With neither flag, a fresh run with a dirty tree asks the user —
  > stash (the default), keep, or abort (`OrcaFlowException`, thrown before any
  > stash or branch mutation) — but only when stdin and stderr are both a
  > terminal, so an unattended run still stashes exactly as before. A run whose
  > own progress log is present stashes regardless, and says `--keep-changes`
  > was ignored: the R2 amendment's resume rationale, extended to a log too
  > broken to read (only the stash reverts a broken in-progress edit to its
  > committed content). Sharp edge, deliberately not mitigated: kept files are
  > unprotected until that first stage commit — a failure before it runs R5's
  > `git reset --hard` and destroys kept modifications to tracked files. Kept
  > untracked files survive: R5's clean is skipped for this run too, for the
  > reason its own text gives.

  > **Amendment (2026-08-27).** `OrcaArgs.worktree` (`--worktree`) runs the flow
  > in `.orca/worktrees/<prompt hash>` of this repository instead of the current
  > checkout. The FIRST run in a freshly created worktree always meets this
  > requirement's clean case: a worktree is created from a commit, so its tree
  > is clean by construction. A REUSED worktree — every resume, and every re-run
  > of the same task text — is subject to the same dirty-tree policy as any
  > other directory: a user's edits, or a SIGKILLed run's partial ones, are
  > still there, and the prompt fires normally. Uncommitted work in the invoking
  > checkout stays there. The run starts on an `orca-worktree-<hash>` branch
  > that orca creates and never deletes; it refuses to move that branch if it
  > has since gained commits. `--worktree` is therefore refused with `--keep-changes` (the
  > files it names are in the other checkout and do not come along) and with
  > `--skip-branch` (git will not check the current branch out a second time in
  > a new worktree). Both refusals happen in `OrcaArgs.parse`, before setup, the
  > banner, or any git call.

  > **Amendment (2026-08-28).** The three flags above no longer reach a run as
  > separate fields: `OrcaArgs.target` is one `RunTarget` —
  > `NewBranch(Uncommitted)`, `CurrentBranch(Uncommitted)` or `Worktree`, which
  > carries neither — so a refused pair has no representation past
  > `OrcaArgs.parse`, which is where it is still refused.
- **R5** — On **successful** exit the progress-log file is removed in a final
  commit, and a feature branch left with no changes other than the progress log is
  deleted (throwaway-branch cleanup). That removal commit is also pushed, but only
  when the branch's upstream still contains the progress log — proof the flow
  itself published it — so teardown never pushes work the run wasn't asked to
  publish. On a **failure** exit the feature branch and
  its committed log are kept intact so the next run can resume; the failed
  stage's uncommitted partial edits are discarded (they re-run on resume).
  Teardown is `git reset --hard`, which covers tracked files only, plus
  `git clean -fd` for the files the stage newly created — the common shape of
  agent output. The clean is scoped so it can only claim the run's own output:
  no `-x`, so gitignored paths (build caches, `.orca/cache/`) stay; `.orca/`
  itself is excluded, since it holds this run's progress log while no stage has
  committed it yet, and other runs' progress logs; and it is skipped entirely
  when setup did not establish a clean tree — a FRESH run that kept a dirty tree
  instead of stashing it (R4 amendment), so those untracked files pre-date the
  run and are the flow's hand-off context, not its output. In that case the
  untracked leftovers stay, and the next run's stash (R4) sweeps them up.
- **R6** — Push and PR creation are flow-controlled and usable at any point; the
  runtime imposes no single terminal push.
- **R30** — On startup the runtime cross-checks the header's recorded branch against
  the working state. If a progress log is found on a branch *other* than the one it
  records — e.g. an in-progress branch was merged, carrying the log into another
  branch — the run aborts with a clear message rather than resuming against the
  wrong branch.
- **R31** — The leading **agent** (the coding harness — `Agent` is an agent, not
  a model: each drives several models) is named by a required `agent` **selector**
  argument to `flow[B](...)` — `FlowContext => Agent[B]`. `B` is the leading agent's
  backend tag, **inferred** from the selector (`_.claude` ⇒ `ClaudeCode`) and never
  written by callers; `flow(OrcaArgs(args), _.claude)` is unchanged. The only way to
  name an agent is an accessor on the flow context (`_.claude`, `_.codex`, …), which
  isn't in scope at the `flow(...)` argument position, so the selector defers
  resolution until the context is built. Inside the body the resolved lead is reached
  via the backend-agnostic top-level `agent` accessor (`def agent(using ctx:
  FlowContext): Agent[ctx.LeadB]`) — an ordinary accessor over `FlowContext`, exactly
  like `claude`/`git` — so the body reads the same regardless of backend; switch the
  selector and the whole flow follows. The threading works because `B` is captured at
  construction into a **type member** `FlowContext { type LeadB }` (a *member*, not a
  parameter, so the whole `using FlowContext` surface stays unparametrised), making
  `agent: Agent[ctx.LeadB]` concretely typed: a session from `agent.session` threads
  into `session.run` and the reviewers (R27). The runtime reads the same lead off
  `ctx.agent` (erased to `Agent[?]`) for branch naming and session rehydration, and
  the in-stage default commit message uses `ctx.agent.cheapOneShot`. `Agent` gains a
  `cheap` method returning the backend's cheap variant (claude → haiku, gemini →
  flash, codex → mini). There is no implicit default agent. (Boundary: the agnostic
  `agent` covers the autonomous, tier-agnostic path; backend-specific tiers —
  `claude.opus` — and interactive planning — `Plan.interactive`, which needs
  `CanAskUser[B]`, defined only per concrete backend — use a concrete accessor.)
- **R32** — The progress header is **untrusted input** on load (the log is
  human-visible and pushable — R26 — so it may be edited). Before any destructive
  action the runtime validates it: `branch`/`startingBranch` must be valid
  branch names (a header holding anything else fails to decode), `userPrompt`
  must equal the current prompt, and it refuses to `checkout`, `reset --hard`,
  or delete a protected branch (the default branch / `main` / `master`).

**Design.**

```scala
def flow[B <: BackendTag](        // B inferred from the selector, never written
    args: OrcaArgs,
    agent: FlowContext => Agent[B],  // required leading-agent selector (R31)
    //   ^ resolved against the built context: `_.claude`, `_.codex`, …
    // … existing tool overrides …
    branchNaming: Option[BranchNamingStrategy] = None,
    //   ^ None ⇒ slug the prompt; or Some(BranchNamingStrategy.issue(handle)) for issue flows
    progressStore: Option[ProgressStore] = None     // §2.4; pluggable path + format
)(body: FlowControl ?=> Unit): Unit  // body is non-generic; `B` is pinned into FlowContext.LeadB
```

Per R31, `agent` is a selector resolved against the built `FlowContext` — the
only way to name an agent is an accessor on the context, which isn't in scope at the
`flow(...)` argument position, so resolution is deferred. The resolved lead is
reached in the body via the `agent` accessor (`Agent[ctx.LeadB]`); the runtime reads
the same lead off `ctx.agent` (erased) for branch naming, and the in-stage
default-commit-message path uses `ctx.agent.cheapOneShot`, overridable per stage via
`commitMessage` (§2.1). The lifecycle therefore builds the context (and
the progress store) **before** running branch setup, since branch naming needs the
resolved model. The body is `FlowControl ?=> Unit`
(R29): a direct `stage(...)` resolves its authority while forks see only
`FlowContext`.

`ProgressStore` (§2.4) is the seam behind R21: the default writes JSON to
`.orca/progress-<hash>.json`; a custom store changes the path or format.

`BranchNamingStrategy` ships the common cases plus a safe slugger, so a branch name
is always a valid git ref regardless of what the prompt or issue title contained:

```scala
object BranchNamingStrategy:
  /** git-ref-safe slug. Lower-cased; `[a-z0-9-]` only; runs collapsed; leading/
    * trailing `-` stripped so the ref never begins with `-` (CLI-flag/argument
    * injection into `git`/`gh`); forced to start alphanumeric; capped to `maxLen`;
    * empty input falls back to `flow-<shorthash>` so a ref is never empty. */
  def slug(text: String, maxLen: Int = 50): String
  /** `<prefix>/issue-<number>` — number is an Int, prefix slugged; safe by construction. */
  def issue(handle: IssueHandle, prefix: String = "fix"): BranchNamingStrategy
  /** Deterministic strategy from arbitrary text, routed through `slug`. */
  def fromText(text: => String): BranchNamingStrategy
  /** Cheap-model shortening of a free-form prompt, then `slug`. */
  val shortenPrompt: BranchNamingStrategy
```

There is no `convention(rawString)` taking a pre-built name: that would invite
`s"fix/$title"`-style interpolation of unsanitised values. Structured inputs
(`issue`) and the slugger (`fromText`) cover the cases without that footgun.

Setup (before the body):

1. Record the starting branch (R3).
2. `ensureClean` — stash a dirty tree with a warning (R4).
3. Resolve the feature branch: if a header already exists (resume), take its branch
   name; otherwise compute the name via `branchNaming` (R2) — the deterministic
   strategies are pure, the prompt-shortening one calls `agent.cheap`. Branch naming is
   a *setup step*, not a committing stage — it runs before the branch exists and its
   result is captured in the header.
4. Fresh run: create + checkout the branch, then write and commit the header (R19).
   Resume: checkout the existing branch — the header is already committed.

Teardown on **success**:

5. Remove the progress-log file in a final commit (R5).
6. If the branch now holds no changes vs. the starting branch (no code was
   committed, only progress entries that step 5 just removed), delete it (R5).
7. Checkout the starting branch (R3); any stash from step 2 can be popped.

Teardown on **failure**:

8. Discard the failed stage's uncommitted partial edits with `git reset --hard`
   (which restores the last committed log), plus a scoped `git clean -fd` — see
   R5 for what the clean spares and when it is skipped. They re-run on resume.
   **Stay on the feature branch** (R3); do not return to the starting
   branch — HEAD is then already where the next run resumes and the committed log is
   in the working tree, so resume needs no branch lookup. The user switches back to
   the starting branch themselves once they've dealt with the failure.

Setup's own git and LLM operations (branch naming, header commit) run with
runtime-supplied tokens (`InStage` for the branch-naming LLM call,
`WorkspaceWrite` for the git writes) — the runtime is the privileged constructor
of both, so it can mutate during setup before any user stage exists. Push and PR
creation remain ordinary stage actions (R6). The leading model (`agent`) and the
resolved branch are exposed through `FlowContext` accessors. The branch-naming
strategy and the progress store are overridable (R21).

### 2.6 Sessions

**Requirements.**
- **R22** — Durable session identity lives in a keyed `SessionRecord`, obtained via
  `agent.session(name, seed): FlowSession[B]` and persisted in the machine-local
  session store (with the client→server map for server-id backends) — never as a
  stage result: neither
  `SessionId[B]` nor `FlowSession[B]` has a `JsonData` given, so neither can be
  smuggled through `stage`'s persistence path, where it would get neither the
  wire-map nor the seed-lookup a `SessionRecord` provides. That blocks the
  stage-result route out of a stage and only that route; a handle stashed in an
  in-memory holder in one stage and read in a later one still compiles, and
  fails loudly (`NoSuchElementException`) on the resume that skips the stage
  that filled it. On resume, whether the *live* backend
  conversation can be continued is decided by a **non-destructive existence probe**
  (`AgentBackend.sessionExists(id)`) rather than guessed: a backend exposes one where it
  can (an on-disk session file, a list command, or a `GET`) — today every backend does.
  If the probe is absent or says gone, resume falls back to re-seed (R23).
- **R23** — A session is obtained via a get-or-create (`agent.session`) keyed by
  `(name, minting stage)` and recorded in the session store under that key, so a retry
  reuses it rather than minting a second. `name` is the role (`implementer`, `final-fixer`) — the
  half that travels, naming the session in the run manifest; the stage half is the path
  id of the stage the call sits in (ADR 0018 §2.1), or the empty string for a mint in
  the flow body outside every stage. The runtime reads it from the open frame stack;
  an author supplies nothing. The two are compared by exact string equality; neither is
  hashed, and neither becomes a filename or a wire token. Consequences: two stages can
  never name one session, so a per-task loop mints `implementer` inside each task's
  stage and gets one conversation per task with no per-task label to compose; inserting,
  reordering or skipping other `session(...)` calls between runs leaves a key alone; and
  moving a mint into another stage, or renaming the stage it sits in — a re-plan
  rewording a task title — is a different session, minted fresh and primed from the seed
  rather than resuming the old task's conversation. Minting one name **twice in one
  stage** throws: two handles writing into one conversation is an authoring mistake, and
  the fix is to rename one or to split the stages. Re-minting a key on **resume** is the
  reuse path, not a duplicate — the run tracks only the keys minted in this execution.
  For a mint inside a stage that duplicate check is sound rather than best-effort,
  because a stage body is all-or-nothing: two mints of one name in one stage either
  both execute or neither does, so the claim set sees both whenever they could
  collide. The flow body's root frame offers no such guarantee — two mutually
  exclusive mints of one name there are never both claimed — which costs the warning,
  not correctness: each still resolves to the single record stored at that key. A flow attaches a
  `seed` — the essential context to rebuild the
  agent if its backend conversation is lost (typically the plan brief, or the
  issue/plan when there is no brief); the runtime additionally prepends a progress
  preamble derived from the log (which stages have completed). **Re-seed is the
  reliable, uniform path**: on resume orca re-mints and primes the session with
  preamble + seed unless a backend existence probe (R22) confirms the recorded session
  is still live, in which case it continues it. `agent.session(...)` is callable
  wherever a `FlowControl` is — inside a stage or at the flow-body top level — and the
  handle it returns is a plain value closed over by the scope that minted it. Mint it
  where it is used: inside the stage that drives it when one stage owns it, outside
  every stage when several share it. A resume that skips a completed stage skips its
  mint with it; a resume that re-runs one re-mints the key onto the recorded id,
  which holds because the record outlives the failure teardown (see the store
  amendment below).

**Design.**

A flow obtains a session via `agent.session(...)` — a *get-or-create* keyed by the
session store, not a plain `new`. It is **pure**: it reserves a session id (a UUID) and
records the key + id + seed in the store; the backend conversation is created lazily on
the first gated `run`. So `agent.session(...)` needs no stage of its own while the actual LLM
effect stays inside one (R15). On resume it returns the recorded id. Naming it
`session` rather than `newSession` reflects the upsert: a retry does not create a
second session.

```scala
val session = agent.session("implementer", seed = plan.brief)
```

`seed` is a string the author composes from whatever the agent needs to rebuild
itself — typically the **plan brief** (or the **issue / plan** when there's no
brief). It is applied on first use, priming the fresh session; if the session is
later lost on resume it is replayed into the re-minted one, with a **progress
preamble** the runtime prepends from the log (e.g. "Completed: planning, task 1,
task 2; resuming at task 3") — so the author needn't track progress in the seed. A
retry that finds the session still alive continues it directly and does not re-send
the seed. The seed is a single composed blob, not a transcript replay: cheap,
predictable, free of re-applying past effects.

On resume orca decides continue-vs-re-seed by a **non-destructive existence probe**
per backend — `AgentBackend.sessionExists(id)` — not by attempting a resume and
catching an error (which some CLIs don't reliably raise). The recorded id (and, for
server-id backends, the client→server map) is persisted in the log and rehydrated,
then probed:

| Backend  | Probe                                                                   |
|----------|-------------------------------------------------------------------------|
| claude   | on-disk `~/.claude/projects/<cwd-slug>/<id>.jsonl` (cwd-scoped; 30-day prune) |
| codex    | on-disk `find ~/.codex/sessions -name "rollout-*-<id>.jsonl"` (global)   |
| gemini   | `gemini --list-sessions` (project-scoped)                               |
| opencode | `GET /session/<id>` → 200 vs 404 (durable across server restarts)        |
| pi       | on-disk `<workDir>/.orca/cache/pi-sessions/<id>/*.jsonl` (workDir-scoped) |

If the probe confirms the session, orca continues it; if it returns absent (or the
backend has no probe), it re-mints and re-seeds. **Re-seed stays the guaranteed
fallback**; the probe only makes "continue the live session" a deterministic decision
rather than a hope. *Implementation notes:* this needs (a) the persisted id/map —
today's `SessionRegistry` is in-memory and not rehydrated from the log; and (b) a
`sessionExists` method on the backend SPI. The probe mechanisms are verified against
current CLIs (claude/codex on-machine; the rest from docs/source); gemini's
list output and opencode's directory-scoping should be pinned when the probes land.

> **Amendment (2026-07-06).** Implementation landed on the `session-identity-fixes`
> branch and supersedes two details above:
> - Each `SessionRecord` is tagged with the minting agent's `backend`, and
>   `FlowLifecycle.rehydrateSessions` replays a record's resume wire id into the agent
>   for *that* backend (untagged/older records fall back to the lead; a tag matching
>   none of the run's accessors is skipped, not guessed) — the lead-only rehydration
>   limitation this section originally shipped with is gone.
> - Durability is declared as one structural choice per backend —
>   `AgentBackend.sessions: SessionSupport[B]`, either `Ephemeral(registry)` (pi) or
>   `Durable(registry, probe)` (claude/codex/gemini/opencode) — rather than the three
>   independently-overridable `sessionExists`/`resumeWireId`/`registerSession` hooks
>   this section describes; see AGENTS.md's "Sessions" section for the current model.

> **Amendment (2026-09-18).** R22/R23 above are as rewritten by stage keying;
> what they replaced, and why, is recorded here.
>
> The key was `(name, detail)`, with `detail` author-supplied prose
> (`s"task ${n + 1}: ${task.title}"` in five shipped flows). It had one live
> defect: `StageFrames.claimedSessionKeys` records only mints that *execute*, so
> on a resume a replayed stage's mint never claims its key, and a colliding mint
> in another stage fell through to the reuse path and adopted the replayed
> stage's session id — two handles, one conversation, no warning. Keying on the
> minting stage makes that shape unrepresentable rather than merely detected, and
> makes the duplicate check sound for the first time (a stage body is
> all-or-nothing, so both mints of a colliding pair run or neither does).
>
> A positional component — "the Nth session named X in this stage" — was
> proposed and rejected. The stage contract guarantees whole-body *replay*, not
> deterministic *re-execution*: a stage that runs, fails and re-runs executes its
> body twice, and the second pass can branch differently on an agent reply or a
> live read. A conditional mint that ran on the first pass and not the second
> shifts every later occurrence index down one, so the re-run's occurrence 0
> adopts the first attempt's occurrence 0 — a different session. That is the same
> silent-aliasing class this change exists to remove, so no occurrence counter.
>
> The path id it keys on carries a positional component of its own, inherited
> from §2.1 rather than introduced here: a stage body that re-runs and opens a
> different number of same-named nested stages on the second pass shifts those
> inner path ids, and the keys of the sessions minted inside them shift with
> them. A slot that replays from the log mints nothing, so a shifted mint lands
> either past everything the first pass recorded — a fresh session primed from
> the seed, the uniform fallback — or on the slot of the stage whose failure
> caused the re-run, continuing that stage's conversation. Two same-named stages
> at one occurrence in one scope being one identity is what §2.1's ids already
> say; the session key now says it too. No shipped flow opens a varying number of
> same-named nested stages.
>
> Identity and label separate here. Identity wants the stage *id*, which is
> unique and carries `#0` occurrence suffixes; a label wants prose. A fused key
> would have to either render the raw id into a picker row
> (`implementer (Task: Add multiply#0)`) or recover the stage name by parsing an
> id that §2.1 declares opaque. So `SessionKey` keeps identity and one diagnostic
> rendering (`describe`), a session reads to a person as its bare `name`, and the
> stage keeps the field it already had in every surface that lists sessions.
>
> `OrcaEvent.SessionCommitted` still carries the whole `SessionKey`. The run
> manifest's `sessionDetail` becomes `sessionStage` (ADR 0021 §8 amendment,
> 2026-09-18) — a rename, not an addition. `orca continue <name>` still addresses
> a session by name alone.

> **Amendment (2026-09-22, layout and vocabulary).** The unit a progress log
> belongs to is a *run*: one prompt's flow execution across every process that
> resumes it, keyed by `RunKey` (the prompt hash). One process is an *attempt*
> (ADR 0021 §8). The log lives at `.orca/runs/<key>.progress.json` and the
> session records at `.orca/cache/runs/<key>.sessions.json`, so the two halves
> of a run's resume state share a key under mirrored directories. Codecs are
> strict — no defaults, no tolerant decoding; an older log reads as `Corrupt`
> and the run starts fresh. `orca.util.JsonFile` is the one read/write path for
> every whole-file document. The 0.x compatibility carve-outs are withdrawn.

> **Amendment (2026-09-18, session store).** `SessionRecord` leaves the committed
> progress log for `.orca/cache/sessions-<prompt hash>.json`
> (`orca.sessions.SessionStore`), keyed by the same prompt hash as the log.
>
> **Why.** `ProgressStore.upsertSession` did not commit, so a record written inside a
> stage rode that stage's completion commit or nothing. A stage that *completes*
> replays on resume and never re-mints, so its record was never read back; a stage
> that *fails* had its record erased by `FlowLifecycle.teardownFailure` →
> `GitTool.discardUncommitted` → `git reset --hard`, back to the last stage commit.
> Since every shipped flow mints inside a stage, R23's reuse branch was unreachable
> in production: a resumed run re-minted and re-seeded a conversation that was still
> alive.
>
> **Why the cache is the right home, not merely a fix.** A backend session id is a
> machine-local handle into the coding agent's own store — meaningless in another
> checkout or on another machine — so it was never branch history. The cache already
> holds the run manifest for the same reason, and survives every way a run discards
> work: `git reset --hard` (tracked files only), the teardown's `git clean -fd`
> (no `-x`, and `.orca` excluded anyway), and the resume-time stash of a dirty tree
> (`git stash push -u` skips ignored paths, and `.orca/cache` self-ignores). A
> `--worktree` run derives the path from the directory it runs in, which is the
> worktree, exactly as the progress log does — so the resumed run reads its own
> records. A process killed before any teardown leaves the file in place, which is
> the point.
>
> **Why not the run manifest.** The manifest is untracked and event-derived too,
> carries `sessionName`, `sessionStage` and a wire id, and `orca continue` reads
> it back across processes — but it recovers sessions for a person choosing one
> to continue, and the store for a run resuming itself. It is keyed by run
> instance (`.orca/cache/runs/<startedAt epoch ms>-<pid>.json`), so a resumed run
> writes a new file and reading records back would mean scanning the runs
> directory and disambiguating; the store's path comes from the prompt hash, as
> the log's does. Manifests are pruned to a bounded number of runs
> (`RunManifestWriter.pruneOldRuns`), so retention can drop a record whose run is
> still resumable. `ManifestSession` carries no seed, and the seed is what
> re-primes a session whose backend conversation is gone. The direction is
> opposite as well: the manifest is output a listener writes for the shell; the
> store is input the run reads back mid-flight (`resolveSessionId`).
>
> **What it costs.** `ProgressLog` loses its `sessions` field. A log an older orca
> wrote still decodes (the array is an unknown key, skipped), so an in-flight run
> survives the upgrade — but its records do not, and it re-mints and re-seeds. That
> is the documented uniform fallback, not a new failure mode: a missing or
> unreadable store reads as no records anywhere.
>
> `teardownSuccess` deletes the store beside the log, so a later run of the same
> prompt starts fresh conversations rather than continuing a finished run's.
>
> The write still takes `WorkspaceWrite`: it is a read-modify-write of one shared
> file, the same race the progress log has. `agent.session(...)` self-mints that
> token through `RuntimeInStage` because it is callable outside any stage, not
> because its write is uncommitted.

> **Amendment (2026-09-18, stage path type).** The stage half of a `SessionKey` is
> `orca.StagePath` — `FlowBody`, or `Stage(path id)` — rather than a `String` whose
> empty value meant "outside every stage". `StagePath.fromValue` is the single place
> a persisted id (absent, empty, or a path) is read back, and `StagePath.child` the
> single place one is built, so `StageFrames`'s frames carry a `StagePath` and
> `enterStage`/`peekStageId` return `StagePath.Stage`. The wire shape is unchanged:
> `SessionRecord.stage` and the manifest's `sessionStage` stay plain strings.

> **Amendment (2026-07-28).** Pi is durable too: the table above no longer has a
> probe-less row, and the 2026-07-06 amendment's `Ephemeral(registry)` (pi) tagging no
> longer holds — every backend is `durable(scheme, probe)`, with `ephemeral` kept as a
> supported shape that has no user today. Pi's `--session-dir` moved from a
> `deleteOnExit` temp dir to `<workDir>/.orca/cache/pi-sessions/<session id>/`, and the
> probe is "some `*.jsonl` in that dir carries a transcript header pi's `--continue`
> accepts" — `type: "session"` with a `cwd` resolving to the workDir, matching pi's own
> filter, so orca never promises a resume pi would silently start empty; some, not
> exactly one, because a first turn that fails after pi seeded the dir is retried fresh
> and seeds a second file, and `--continue` picks the most recent. Consequence: pi rows
> in the run manifest now carry a wire id.
> Accepted as-is: pi migrates older session-file versions on load, so a dir written by
> an earlier pi is upgraded rather than rejected. Retention: pi doesn't prune the dir
> orca points it at, so orca's session store (`PiSessionStore`, which also owns the
> probe predicate) does — when the runtime builds the backend, before any probe can
> run, it best-effort deletes session dirs untouched for 30 days (matching claude's own
> transcript retention). Pruning a session is not a lost turn: the probe then reports
> absence and the runtime re-seeds, and the probe applies the same cutoff, so a dir
> another process is about to prune already reads as absent.

> **Amendment (2026-09-21, carried-over live conversations).** A durable
> session is told, on the first turn this run takes against it, that the working
> tree holds only what earlier stages committed — whenever its record carries a
> `resumeWireId` (a previous run committed a turn there) and the probe says that
> conversation is still live.
>
> **That pair is the trigger, not "the run resumed a log".** The two come apart:
> a corrupt log makes the run bind fresh, while the session store, which is not
> the log and outlives it, still holds the carried-over conversations — and they
> are told. A run-level "am I resuming" flag would miss them.
>
> **Why.** The seed and the progress preamble above are applied only when the
> conversation is NOT live, which is backwards for the one case where the
> agent's memory is stale. On resume the conversation survives — the run
> continues it rather than re-seeding — while the interrupted stage's
> uncommitted edits do not: setup stashes the dirty tree (R4), and a previous
> attempt that reached its failure teardown had already reset it. Nothing told
> the agent. Observed live: a coder answered "already done — I created both
> files in my previous turn", the stage reviewed an empty diff, and the run
> recovered only because a reviewer reported the file missing.
>
> **Once per session, not per turn.** `reviewThenFix` drives one session for
> several fix turns inside a stage. From the second turn on, the uncommitted
> edits in the tree are that turn's own work, so repeating the notice would
> report missing work that is not missing. A session whose conversation was
> lost is not told either: it re-seeds, and the preamble already says an
> unfinished stage left nothing behind.
>
> **The stash is not mentioned.** It exists only when the previous attempt was
> killed rather than torn down; popping it can conflict, and the person — who
> is told about it, with `git stash pop`, in the resume banner — is the one who
> can judge that. Redoing the work is what the agent can do unaided.
>
> **Adopted chats are not told.** `agent.chat(session.id)` continues the same
> conversation as an ephemeral `Chat`, which needs only `InStage` and so runs
> inside a fork; the claim and the notice sit behind `FlowControl`, which a fork
> never holds — moving them to the chat door would hand a fork the flow thread's
> per-run state. So a flow continuing a carried-over conversation that way is
> untold, and a durable turn after such a chat turn is still that conversation's
> first, telling it uncommitted edits are gone while the chat turn's edits sit in
> the tree. What the notice asks for — read the files — stays right in both
> cases.
>
> **Two limits of the premise.** A finished run's teardown discards its session
> store best-effort, so a discard that fails leaves the next run of the same
> prompt told a previous attempt was interrupted. And a turn that suppresses its
> prompt event (`emitPrompt = false`, as the fix turn does) carries the notice
> without it appearing in the transcript.

> **Amendment (2026-09-21, a session the backend already holds).** The R22
> probe also decides fresh-vs-resume for a session with NOTHING recorded, on a
> `ClientClaimed` backend. `AgentBackend.sessions` answers one of three
> (`Continuation`): `Recorded` — live under the wire id recorded for this
> client; `Claimed` — live under the client's own id, with nothing recorded;
> `Rebuild` — re-seed. `dispatchFor` reads the same resolution, so `Claimed`
> resumes rather than re-claims.
>
> **Why.** `resumeWireId` is committed only after a clean drain, so a run
> interrupted during a session's FIRST turn records nothing — while the backend
> has already written the transcript for the id orca put on the wire. Claude and
> pi claim ids client-side, and claude refuses `--session-id` for a session it
> already holds: the next run dispatched `Fresh(claim)` and died there. Not a
> degradation, a failed run. It was masked while records lived in the committed
> log, where the failure teardown's `git reset --hard` erased a failed stage's
> record and the resume minted a fresh uuid; moving them to the cache (the
> 2026-09-18 store amendment) made every in-stage session reach it. The same
> resolution also covers the retry inside one run (`AgentCall` re-prompts on a
> failure the model didn't cause), which re-claimed the same id.
>
> **Why resume rather than mint a fresh uuid.** The interrupted turn's prompt
> carried the seed, so the conversation the backend holds is already primed; a
> fresh mint would re-seed it and drop that. And nothing distinguishes an
> interruption during turn one from one during turn two, which resumes — the
> probe confirming the session IS this section's continue-vs-re-seed decision.
> Re-seed stays the fallback wherever the probe says gone.
>
> **The notice follows.** A conversation resumed this way predates this run just
> as a recorded one does, so the carried-over condition above widens: a recorded
> `resumeWireId` OR `Claimed`. The two are disjoint and neither fires for a
> conversation this run opened itself — a turn this run took records a mapping,
> an adopted `chat` turn records one too, and both then read `Recorded` against
> a record with no wire id.
>
> **What it costs.** One probe per turn while nothing is recorded (a file
> existence check for claude, a session-dir read for pi), which the first
> commit ends. A server-minting backend never reaches it: its client id goes
> nowhere near the wire, so a probe on it would answer about nothing.
> `SessionId.isSafe` gates the claim as it gates the recorded map's write
> doors, since the id comes back from the session store.

> **Amendment (2026-09-23, one answer per turn).** `Continuation` and
> `Dispatch` merge into one `Dispatch`: `Fresh(claim)` — re-seed — or
> `Resume(wireId, origin)`, where `origin` is `ThisRun` or `EarlierRun`. The
> prompt side (re-seed, and the carried-over notice iff `EarlierRun`) and the
> argv side read that one value.
>
> **Why.** The prompt side probed a recorded wire id; the argv side resumed any
> recorded id unprobed. A lost conversation was re-seeded AND resumed — `--resume`
> of a gone id, which fails the turn — and a probe run three times per turn could
> answer differently each time.
>
> **How.** Rehydration records a wire id as unconfirmed. The first
> `dispatchFor` probes it once: live, it settles as `EarlierRun` and every later
> ask reads that; gone, the mapping is dropped, so the re-seeded turn's commit
> records its new id. A held claim settles the same way. An id this run
> committed is not probed. `AgentBackend` resolves once per backend call and
> hands the value to both the enforcement notice and the spawn. A `Fresh` answer
> is not stored — a failed first turn can leave a claim the next ask must see —
> so on a `ClientClaimed` backend the backend call runs the claim probe again.
>
> **What it costs.** On claude and pi a rehydrated id is now probed before it is
> resumed, so a probe false negative dispatches `Fresh(claim)`, which those CLIs
> refuse for an id they hold. The claim probe already carried that risk; both
> read the same transcript path the spawn writes.

### 2.7 External-effect idempotency

**Requirements.**
- **R24** — Externally-observable GitHub effects are idempotent by default, to
  survive the non-atomic window between an effect and its progress commit:
  `createPr` reuses an existing open PR matched on the **head branch** (orca creates
  exactly one PR per feature branch, so head alone is unambiguous; a head-*and*-base
  match would only matter for stacked-PR setups, which orca never produces);
  `upsertComment(marker,
  …)` accompanies append-only `writeComment`, where the **marker embeds the prompt
  hash** so it's unique per flow and can't collide with another run's (or a
  third-party) comment. The progress entry is committed immediately after the effect.
- **R26** — A *merged* PR must not contain the progress log: the final cleanup
  commit (R5) removes it, so a clean branch merges clean. While a PR is open
  mid-flow the file is present in the pushed history; this is accepted as a known
  limitation (§5) rather than rewriting history on every push. The remote is
  brought in line by the gated teardown push (R5), which completes a publication
  the flow already made and so leaves R6's "no runtime-imposed terminal push"
  intact.

**Design.**

`gh.createPr`, on hitting GitHub's "a PR for this branch already exists", looks up the
open PR for this head branch and returns it instead of failing — so a resume after "PR
created but progress commit lost" reuses rather than duplicates. (Matching on the head
branch is sufficient because orca owns one feature branch per flow and opens a single
PR from it; head+base matching would only be needed to disambiguate stacked PRs, which
this runtime never creates.) `gh.upsertComment(marker, body)` finds a prior comment carrying `marker` and
edits it in place; the marker embeds the prompt hash so it's unique to this flow
(not forgeable into another run's comment). Plain `writeComment` stays append-only
for genuinely additive comments. The stage commits the progress entry immediately
after the effect, narrowing the non-atomic window to the smallest possible.

### 2.8 Replacing `Plan` persistence

**Requirements.**
- **R25** — The stage log is the sole resume mechanism. `Plan.recover`,
  `.orca/plan-<hash>.md`, and checkbox-ticking *as resume state* are retired;
  `Plan`'s generation API is kept and its task loop collapses to per-task stages. A
  human-readable progress checklist may still be rendered as a convenience, but it
  is cosmetic — never read back for resume.

**Design.**

`Plan.recover`, `recoverOrCreate`, the `.orca/plan-<hash>.md` file, and
checkbox-ticking are removed. The plan becomes the result of a `stage("Plan")`, and
completion is stage completion in the log. `implementTaskLoop` collapses to:

```scala
for task <- plan.tasks do
  stage(s"task: ${task.title}")(/* implement + review, under this stage */)
```

`Plan`'s generation grid (`autonomous` / `interactive` × `from` /
`assessThenPlan` / `triage`) is unchanged except that **every generated plan now
carries its brief**: the `Plan` / `PlanWithBrief` split and the explicit `.briefed`
step are removed, so `plan.brief` is always available (it feeds the session seed,
§2.6). This is a small `Plan`-API simplification adjacent to this work.

A flow may still render a human-readable checklist for users — a ticked task list in
the PR body, or a committed `PLAN.md` updated as part of each task stage's commit.
This is purely cosmetic: resume reads the stage log, never the checklist, so the two
cannot desync into a wrong resume.

---

## 3. Example flows (after the refactor)

Illustrative flow scripts only. Reads run outside stages; side-effecting work is
staged and resumable; the branch is auto-created from the prompt and auto-deleted
if nothing of substance happens; pushes are mid-flow.

### 3.1 `implement.sc`

```scala
//> using dep "org.virtuslab::orca:0.2"
//> using jvm 21
import orca.{*, given}

flow(OrcaArgs(args), _.claude):                          // required agent selector
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, agent).value        // Plan (always briefed; has JsonData)

  // Get-or-create the implementer session (pure: id reserved, backend created on
  // first use). Minted outside every stage, so its key is the flow body's and the
  // task stages below share it. The seed (plan brief) primes it on first use, and is
  // replayed if the backend session is lost on resume.
  val session = agent.session("implement", seed = plan.brief)

  for task <- plan.tasks do
    stage(s"task: ${task.title}"):                       // skipped on resume if already done
      session.run(task.description)
      reviewAndFixLoop(                                   // runs under this stage (using InStage)
        coderSession = session,
        reviewers = allReviewers(agent),
        reviewerSelection = ReviewerSelector.agentDriven(agent.cheap),
        task = task.title.value,
        formatCommand = Some("cargo fmt")
      )
      // one commit per task: code + progress entry
```

`git.commit` requires `WorkspaceWrite`; `session.run` (which also needs
`FlowControl`) and `reviewAndFixLoop` require `InStage` — all supplied within
a `flow`'s enclosing `stage`. There is no plan
markdown file — the progress log subsumes it, which also removes any checkbox
state to keep in sync (a human-readable checklist, if wanted, is cosmetic — §2.8).

`issue-pr.sc` adds only two patterns over 3.1: it picks the deterministic
`BranchNamingStrategy.issue(issueHandle)` so the branch is named from the issue
up-front, and a read-only assess stage that may reject (post a comment, return
`None`, and let the throwaway branch be deleted on exit) before a trailing
`stage("Open PR")` that pushes then `gh.createPr` (idempotent by branch). It is
otherwise 3.1's task loop.

### 3.2 `issue-pr-bugfix.sc`

The hard case: it may early-exit (post a comment, no code), and it pushes
mid-flow — first the failing test, then the fix.

```scala
// Parse the handle up-front so it can seed the deterministic branch naming.
val orcaArgs    = OrcaArgs(args)
val issueHandle = IssueHandle.parseOrThrow(orcaArgs.userPrompt)

flow(orcaArgs, _.claude, branchNaming = Some(BranchNamingStrategy.issue(issueHandle))):
  val issue   = gh.readIssue(issueHandle)                            // read
  val session = claude.session("fix", seed = issue.body)             // get-or-create
  val triage  = stage("Triage"):                                     // LLM → staged
    Plan.autonomous.triage(report(issue), claude).value

  triage match
    case Triage.NotABug(why) =>
      stage("Comment: not a bug")(gh.writeComment(issueHandle, why)) // throwaway branch, deleted on exit

    case Triage.Untestable(_, steps) =>
      stage("Comment: repro steps")(gh.writeComment(issueHandle, steps))

    case Triage.Testable(summary, _, failingTestPath) =>
      stage("Write failing test"):                                   // commits the test
        session.run(s"Write the failing test at $failingTestPath …")

      val pr = stage("Push + open tentative PR"):                    // later stage: the test is committed
        git.push().orThrow                                           // push #1
        gh.createPr(title = summary, body = "Failing test only.").orThrow  // idempotent by branch

      if gh.waitForBuild(pr, 30.minutes).orThrow.outcome == BuildOutcome.Success then  // read
        fail("CI passed on the failing-test commit — the reproduction is wrong.")
      display(s"CI red on ${pr.shortRef} — reproduction confirmed")  // progress-only

      stage("Confirm repro")(/* sonnet inspects the run via gh, posts a comment */)

      val fixPlan = stage("Plan the fix"):
        Plan.autonomous.from(s"Fix ${issueHandle.shortRef}; a failing test exists.", claude).value
      for task <- fixPlan.tasks do
        stage(s"task: ${task.title}")(/* implement + reviewAndFixLoop, under this stage */)

      stage("Push fix + finalise PR"):
        git.push().orThrow                                           // push #2
        gh.updatePr(pr, title = summary, body = "Failing test + fix.")
```

> **Authoring rule (R8).** A stage commits only on completion, so any operation
> needing an existing commit — `push` above all — must live in a *later* stage than
> the code that produced it. A push in the same stage as the edit would push
> nothing. (This and the other authoring rules go in the README — Epic G.)

---

## 4. Implementation

Sequenced as Epics A–G in the task-level plan `plan-stage-runtime-impl.md` (file map,
per-task TDD steps, dependency order). Summary:

- **A — `JsonData` for stage results.** Givens for primitives / `Unit` / `Option` /
  `List` / small tuples; a sum codec for the sealed `PlanLike`. No new typeclass;
  deliberately no given for `SessionId[B]` (§2.3, §2.6, R22). Self-contained; lands
  first.
- **B — Capabilities + tool gating.** `FlowControl`, `InStage`; `(using InStage)` on
  the §2.2 methods (plus `gh.upsertComment` and `createPr` reuse); thread `InStage`
  through side-effecting helpers, `FlowControl` through stage-starting ones (the
  single `InStage` was later split into `InStage`/`WorkspaceWrite` — §2.2, §6). The
  widest, compiler-guided change. Negative compile tests: `git.commit` outside a
  stage, and `stage` inside a fork, must fail to compile.
- **C — Progress log model + store.** `ProgressHeader`/`StageEntry`/`ProgressLog`;
  `ProgressStore` default at `.orca/progress-<hash>.json`; recovery + untrusted-header
  validation (R32).
- **D — Stage runtime + resumption.** `stage`/`display`/`fail`; decode-or-rerun;
  per-stage commit; the `sessionExists` probe on the backend SPI and a persistable
  `SessionRegistry`; `agent.session` / `agent.cheap`; re-seed on resume.
- **E — Flow lifecycle + config.** `flow(...)` setup/teardown, `FlowControl`
  provision, `BranchNamingStrategy`, mandatory leading `agent`.
- **F — Replace `Plan` persistence; migrate examples.** Retire `Plan.recover` etc.;
  always-briefed plans; convert the example flows.
- **G — User documentation.** README coverage of the authoring rules, the capability
  model, resume semantics, and the accepted limitations.

Dependency order: A, B, C independent; D needs A+B+C; E needs B+C+D; F needs D+E; G
alongside.

---

## 5. Consequences — risks & accepted limitations

- **Resumability becomes uniform** across every side-effecting step, not just tasks —
  the bugfix flow's triage/CI phases now resume.
- **Progress log in the open PR (R26) — unsolved.** Committed-on-branch gives
  atomic code+progress, but every mid-flow push carries `.orca/progress-<hash>.json`
  into the PR until the final cleanup commit removes it. Stripping it per-push would
  rewrite history and break resume; this is accepted as a known wart of the "keep it
  committed" choice. Merged PRs are clean (R26); open ones are not. Beyond tidiness
  it is a **confidentiality** surface: stage results (LLM outputs, issue bodies) and
  commit messages are serialised verbatim into the log and pushed history (and
  survive in reflog/forks after the cleanup commit), so a flow must not return
  secrets as stage results. A redaction hook is possible but out of scope for v1
  (§6).
- **Capabilities are leaky pre-capture-checking.** Both the stage tokens (no
  side effects outside a stage) and `FlowControl` (no stage inside a fork) prove
  *lexical* enclosure, not the real invariant: a token can be captured and used
  where it shouldn't (a token smuggled out of a stage; a `FlowControl` closed over
  inside a `parallel` fork). They are kept for the compile-time signal —
  guard-rails, not yet guarantees. *The fork half of this leak is since closed
  (§6): separation checking rejects a fork capturing `WorkspaceWrite` or
  `FlowControl` at the checked fork funnel (raw `ox.fork` stays unchecked until Ox
  adopts capture checking); temporal escape past a stage remains convention.*
  Gating is also the widest change in the project (four tool traits, ~8 helpers,
  every flow script); acceptable here because breaking changes are fine at this stage.
- **Resume is decode-safe, not meaning-safe.** A stored result that no longer decodes
  to the call-site type re-runs the stage; but a stage whose *meaning* changes under
  a stable name and a still-decodable type silently reuses the stale value — the more
  likely edit between a crash and a resume. Relatedly, inserting a *same-named* stage
  before existing ones at the *same nesting level* shifts later occurrence-indices
  within that frame (R10), so their stored entries no longer match and that work
  re-runs — harmless but worth knowing.

  > **Amendment (2026-07-07).** An earlier version of this note also treated a
  > *nested* stage's occurrence shift as harmless. It was not: with the former
  > flat, un-nested id scheme, a stage that skipped on resume dropped its nested
  > stages' occurrence bumps, so a later same-named stage recomputed a nested
  > stage's id and either silently replayed its stale value (same result type) or
  > clobbered its record on re-run (different type) — a genuine misattribution,
  > not a harmless re-run. Hierarchical path ids (R10, §2.1) fix this: a nested
  > stage's id carries its parent's segment, so it can never collide with a
  > top-level (or differently-nested) stage of the same name. Sessions need no
  > analogous scheme: a key names the stage that minted it (R23) and nothing about
  > the mint's position within it, so a skipped stage shifts nothing.
- **Session identity follows the stage, not the call's position in it.** A session
  resumes only where its `(name, minting stage)` key still matches the log (R23). The
  shipped flows name a task's stage after the task, so a re-plan that rewords a task
  renames its stage and gives it a fresh session, primed from the seed. That is the
  intended reading — the recorded conversation belonged to the task as it was worded
  before — but it means a re-plan costs the history of every task it rewords, and a
  flow that names its task stages by index instead keeps the history at the price of
  resuming a reworded task on the old wording's conversation.
- **Resume preserves files, not agent context.** File state is committed and
  restored; the LLM conversation is not. After a crash the re-seed blob restores
  only the seed (e.g. the plan), so a long implementer session resumes materially
  "dumber" — the seed must carry enough for the remaining tasks to stand alone.
- **Cross-backend live-session resume is non-uniform.** A non-destructive existence
  probe exists for every backend (on-disk file, `--list-sessions`,
  `GET /session/<id>`), but it needs the persisted id/map (today's
  `SessionRegistry` is in-memory) and carries caveats — claude is cwd-scoped + 30-day
  pruned, gemini's id↔file is fuzzy (use the list, not the file), opencode is
  project-scoped. So live continuation is a per-backend optimization gated on the
  probe, and **re-seed remains the path that holds on every backend** (R22/R23/§2.6).
- **Branch naming: only the prompt-shortening strategy is non-deterministic.** The
  deterministic strategies (`issue(handle)` → `fix/issue-42`, `fromText`) are
  recoverable by recomputation; the prompt-shortening one (for free-form `implement`
  prompts) is why the header persists the once-computed name for read-back on resume.
- **`JsonData` coverage.** Sealed `PlanLike` needs jsoniter sum-type config;
  primitives/tuples need hand-written `JsonData` givens; codecs must be lossless (R9).
  `SessionId[B]` and `FlowSession[B]` deliberately have none (§2.3, §2.6, R22).
- **Custom external effects.** Built-in idempotency covers `createPr` /
  `upsertComment` (R24); a flow's own irreversible side effect must be made
  idempotent by its author or it repeats on resume.
- **Stage ordering discipline.** Push-after-commit (§3.2) and no-concurrent-stages
  (R12) are authoring rules the compiler cannot enforce.

---

## 6. Out of scope & future work

Deliberately deferred; recorded here so nothing is lost:

- **Capture-checking migration.** Shipped (Epic 0, `complexity-review-2` branch;
  commits `7437569`, `365ea76`, `4011187`, `0e8501d`). The convention guard-rails
  are now compile-time guarantees, via the split the 2026-07-06 amendment below
  called for: `InStage` extends `caps.SharedCapability` — the LLM-call gate,
  covering **any** LLM call regardless of toolset (owner decision, 2026-07-07:
  no toolset-aware typing, because capability types cannot constrain what the
  agent subprocess does — that's `AgentConfig.tools` plus the `Enforcement`
  matrix — and adversarial agent behavior is outside the threat model) — and
  stays freely fork-capturable, matching the reviewer fan-out's load-bearing
  capture. `WorkspaceWrite` (new) and `FlowControl` extend
  `caps.ExclusiveCapability` — workspace/index-mutation and stage-starting
  authority — so separation checking rejects two concurrent forks capturing
  either. `stage(...)` bodies receive both tokens; both mint only through the
  `RuntimeInStage` single door (`token()` / `workspaceToken()`). The checked
  fork funnel is `CheckedPar` (`tools`, package `orca`, not on the exported API
  surface) — CC-compiled wrappers around Ox's combinators, and the only
  load-bearing enforcement point until Ox itself adopts capture checking
  (upstream plan, owner 2026-07-07: once `fork`/`supervised` track the `Ox`
  capability, the fork-boundary rejection moves to `ox.fork` directly and
  `CheckedPar` stops being load-bearing); raw `ox.fork` / plain Ox flows remain
  the unchecked escape hatch until then. Additive — no call-site changes beyond
  the two-token split (§2.2, §5).

  > **Amendment (2026-07-06).** As written, this endgame is too blunt: the reviewer
  > fan-out (`ReviewLoop`, via `Flow.mapParUnordered`) *deliberately* captures the
  > ambient `InStage` into each parallel fork, because a gated LLM call
  > (`(using InStage)`-gated `run`) must be reachable from inside those forks —
  > that capture is load-bearing, not a smuggling bug. A rule that types every fork
  > thunk as pure would outlaw it along with the mutations it's meant to catch.
  > The real distinction is between two things `InStage` currently authorizes
  > together: capabilities that mutate shared, index-like state (git/store writes)
  > — which must NOT cross a fork boundary, since concurrent forks racing on the
  > same progress log or git index is exactly what capture-checking should catch —
  > and capabilities that merely gate an LLM call — which MUST cross, since
  > parallel reviewers are the point. Capture-checking work should split these
  > before it lands, e.g. via a future `Sharable` sub-capability that is safe to
  > capture into forks while plain `InStage` (or an index-mutating capability
  > carved out of it) stays fork-opaque. No implementation decision is made here;
  > this only records the constraint so the eventual capture-checking design
  > doesn't calcify the wrong rule.

  > **Amendment (2026-07-07).** The above constraint is resolved by the shipped
  > design (main text above), and the following was verified along the way, on
  > stable Scala 3.8.4 (no flags, no nightly):
  > - `caps.Capability` is sealed — extend the sub-markers, never the root.
  >   `caps.SharedCapability` is non-experimental, so `InStage.scala` carries no
  >   language import; `caps.ExclusiveCapability` is `@experimental`, so the
  >   `WorkspaceWrite` and `FlowControl` files carry
  >   `import language.experimental.captureChecking`.
  > - Taint is narrower than first assumed: a plain consumer of a CC-importing
  >   file's definitions compiles under zero flags (pinned by
  >   `NoTaintCanaryTest`) — but a signature carrying a capture-set parameter
  >   (`C^`, as `CheckedPar.mapParUnordered` does) is only *callable* from a
  >   CC-enabled compilation unit. That's why `CheckedPar` stays off the
  >   exported API surface.
  > - Enforcement is per compilation unit, at the **caller's** site: both the
  >   wrapper file and every calling file need the language imports; a user
  >   `.sc` script only gets fork-capture checking if the script itself carries
  >   them.
  > - Separation checking does not fire through a non-CC-compiled library's
  >   combinators (verified against an Ox-shaped jar) — so `CheckedPar`'s
  >   wrappers are load-bearing, not cosmetic, until Ox ships its own capture
  >   checking.
  > - munit's `compileErrors`/`typeCheckErrors` are blind to capture/separation
  >   violations (capture checking runs post-typer); the CC negative suite
  >   instead invokes `dotc` in a separate-compilation harness (flow test scope)
  >   and pins three cases: a fork capturing `InStage` compiles (protecting the
  >   load-bearing reviewer fan-out), and forks capturing `WorkspaceWrite` or
  >   `FlowControl` fail with "Separation failure".
  > - Tooling caveats worth recording: scalafmt 3.8.3 cannot tokenize `^`
  >   (`CheckedPar.scala` is excluded from formatting, the one hand-formatted
  >   file); tapir's `derives JsonData` is CC-incompatible (keep such
  >   derivations out of CC-compiled files); and a `Test / resourceGenerators`
  >   task that reads `fullClasspath` deadlocks sbt silently — use
  >   `dependencyClasspath` instead.

- **Progress-log redaction hook.** A seam to redact/secret-scrub stage results before
  they are committed, addressing the open-PR confidentiality surface (§5, R26).
- **Nested, repeated, or concurrent `flow(...)` calls.** A flow binds one branch and
  one log; a flow nested inside another, or two runs sharing one working tree / git
  index / `.orca/` path (e.g. the same prompt re-run while the first is still alive),
  is undefined here. The lifecycle must at minimum not corrupt an outer/other flow's
  state, but multi-flow semantics (and any locking) are deferred.
- **Cross-machine resume.** Backend LLM sessions are local; resuming on a different
  machine always falls back to the re-seed path (R23). Persisting/transferring backend
  sessions across machines is out of scope.
