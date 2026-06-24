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
  `InStage`), stage results (`JsonData`), and session ids (`SessionId[B]`,
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
- **R10** — A stage's id is its name plus an occurrence index (disambiguating
  duplicates), never a global execution counter.
- **R11** — On resume, a stage whose recorded result decodes to its call-site type
  is skipped (stored value returned); a result that fails to decode (the stage's
  type changed) re-runs the stage — fail-safe over silent misattribution.
- **R12** — Stages may nest on one thread (each nested stage commits) but must not
  run concurrently — two stages committing at once would corrupt the shared git
  index. This is structural: starting a stage needs `FlowControl` (R29), and
  concurrency combinators hand forks only the thread-safe `FlowContext`, never
  `FlowControl`, so a fork (e.g. a parallel reviewer) cannot start a stage. It is a
  convention today (a fork could still lexically capture an outer `FlowControl`) and
  becomes compiler-enforced under capture checking.
- **R13** — The commit message defaults to an `llm.cheap` summary of the diff; a
  stage may pass `commitMessage: Option[T => String]` to derive it from its result.
- **R14** — `display(...)` gives progress-only output: no stage, id, commit, or log
  entry.

**Design.**

```scala
def stage[T: JsonData](name: String, commitMessage: Option[T => String] = None)
    (body: InStage ?=> T)(using FlowControl): T
def display(message: String)(using FlowContext): Unit
def fail(message: String)(using FlowContext): Nothing
```

A `stage` runs `body` with `InStage` evidence in scope, then records and commits:

1. **Resume check.** Compute the id. If the log holds a completed entry for it,
   decode the stored JSON with `JsonData[T].codec` and return it without running
   `body`. If decoding fails (the script changed the stage's result type under this
   id), run `body` instead — fail-safe over feeding back a wrong value.
2. **Run.** `body` executes; its mutating operations consume the supplied
   `InStage`.
3. **Record & commit.** Append a `StageEntry` (id, name, result JSON), force-add the
   log, then make one commit covering the log delta plus any code changes. The
   message is `commitMessage(result)` if the stage supplied one, otherwise an
   `llm.cheap` summary of the changed files. The log always changes, so the commit
   is never empty.

`display` shows a progress line without checkpointing; `fail` emits an error and
throws, unwinding to the failure teardown (§2.5). Both need only `FlowContext`, so
they're callable anywhere — outside a stage, or inside a fork.

Resume is uniform across stage kinds: an interactive stage (a planning
conversation the user drove) is skipped on resume just like any other, returning
its stored result — the user is not re-prompted for work already done.

**Id.** `id = name + "#" + occurrenceIndex`, where the index counts prior stages
with the same name in this run. Because the key is the name rather than an
execution-order counter, inserting, removing, or reordering *other* stages between
runs does not shift a stage's id: a removed stage leaves a harmless orphan entry; a
newly inserted stage simply has no recorded result and runs. A stage whose dynamic
name changes between runs (e.g. a task title from a regenerated plan) re-runs —
fail-safe.

**Nesting.** Nested stages each commit, so nest only to introduce an extra
checkpoint; wrapping a stage solely around other stages yields a commit carrying just
that stage's progress entry. Why two stages can't run concurrently — the
`FlowControl`-vs-`FlowContext` capability split — is in §2.2 (R12).

### 2.2 Capability gating

**Requirements.**
- **R15** — Every side-effecting operation — file writes, git mutations, GitHub
  mutations, and **all** LLM calls — requires `InStage` evidence, which only the
  runtime can construct and which a stage body receives as a context parameter.
- **R16** — Pure reads (`fs.read`, `git.diff` / `log` / `currentBranch`,
  `gh.readIssue` / `buildStatus` / `waitForBuild`) do not require `InStage` and are
  callable outside stages.
- **R17** — Library helpers that perform side effects take `(using InStage)` and run
  under the caller's stage rather than opening their own committing stages, so a
  task still yields a single commit. A helper that itself *starts* stages instead
  declares `using FlowControl` (R29), making that visible in its signature.
- **R29** — Starting a stage requires a `FlowControl` capability, where
  `FlowControl <: FlowContext`: everything a `FlowContext` is (reads, `llm`, `emit`)
  plus the authority to open a stage — but **thread-affine**, never handed to a fork.
  `flow` provides it; `stage` requires it. At a direct `stage(...)` in a flow body it
  resolves implicitly (zero ceremony); a stage-starting *helper* spells out
  `using FlowControl`, so the fact is visible in its type.

**Design.**

```scala
trait FlowContext                       // thread-safe, shareable: reads + llm + emit
trait FlowControl extends FlowContext   // + authority to start stages; thread-affine
opaque type InStage                     // in-stage mutation token, from `stage(...)`
```

Three capabilities, all constructible only inside `orca`:

- **`FlowContext`** — the narrow, thread-safe context (tool reads, `llm`,
  `userPrompt`, `emit`/`display`). Safe to share into parallel forks, so concurrent
  reviewers each use it.
- **`FlowControl`** — a *subtype* of `FlowContext` adding the authority to start a
  stage. Subtyping (not a derived given) is the point: a `FlowControl` satisfies any
  `using FlowContext`, and the **downgrade is a one-way upcast** — concurrency
  combinators run each fork with only the `FlowContext` (`val ctx: FlowContext =
  control`), so `stage` (which needs `FlowControl`) cannot be called in a fork.
  Pre-capture-checking this is convention (a fork could lexically capture an outer
  `FlowControl`); capture checking later makes it a hard error (see below).
- **`InStage`** — the in-stage mutation token (R15), handed to a stage body by
  `stage`, in the spirit of Ox's `using Ox`.

Every side-effecting tool method gains a `(using InStage)` clause. The methods gated:

- `GitTool`: `createBranch`, `checkout*`, `commit`, `push`, `addWorktree`,
  `removeWorktree`, `ensureClean`.
- `FsTool`: `write`.
- `GitHubTool`: `createPr`, `updatePr`, `writeComment`, `upsertComment` *(new)*.
- Every `LlmTool` / `LlmCall` / `AutonomousTextCall` entry point (LLM calls are
  side-effecting: cost and non-determinism).

Side-effecting library helpers (`reviewAndFixLoop`, `fixLoop`, `lint`,
`summarisePr`, `Plan` generation, the reviewer fan-out) take `(using InStage)` and
run under the caller's stage, so the compiler enforces "no mutation outside a
stage" while a whole task still produces one commit.

Both `InStage` and `FlowControl` are guard-rails today — a token can be captured and
used out of scope (an `InStage` smuggled past a stage; a `FlowControl` closed over in
a fork). The move to a real guarantee is additive: mark each a `caps.Capability` and
type the escaping positions (stage bodies, fork thunks) as pure, so escape becomes a
compile error; call sites don't change. (This is the §5 "leaky pre-capture-checking"
limitation and the §6 future-work item.)

### 2.3 Stage results

**Requirements.**
- **R9** — A stage's return value must be JSON-serialisable, witnessed by `JsonData`
  (the existing tapir-`Schema` + jsoniter-codec bundle). Its codec must round-trip
  losslessly (a resumed value equals the original). A return type without a
  `JsonData` instance is a compile error.

**Design.**

Stage results reuse the existing `JsonData[A]` (tapir `Schema` + jsoniter codec)
rather than a dedicated typeclass — `stage[T: JsonData]`. Every type that already
travels through LLM calls (`Plan`/`PlanLike`, `Issue`, `PrHandle`, `IgnoredIssues`,
…) is therefore a valid stage result with no extra work, and `derives JsonData` on a
new case class is enough. The persistence path uses only the codec; the `Schema`
rides along unused — a fair price for not maintaining a second typeclass.

The library adds `JsonData` givens for the handful of non-case-class results flows
return — primitives, `Unit`, `Option`, `List`, small tuples, and the opaque
`SessionId[B]` (`JsonData.derived` only covers `Mirror` types). A
return type with no `JsonData` instance — a live handle, a closure — fails to
compile, the intended boundary. Codecs must round-trip losslessly: a resumed run
reads the value back from JSON, so a lossy codec would diverge from a fresh run. A
human-readable summary for the log and `display` reuses the existing `Announce`
typeclass where one is in scope.

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
  def writeHeader(header: ProgressHeader)(using InStage): Unit
  def appendEntry(entry: StageEntry)(using InStage): Unit   // upsert by id, last write wins

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
knowing which branch the work is on. `appendEntry` upserts by id: a fail-safe re-run
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
- **R2** — The feature branch is created up-front, before the body runs, from the
  current HEAD, via a pluggable `BranchNamingStrategy`. Built-ins cover the common
  cases **safely**: **deterministic** ones derive a git-ref-safe name from structured
  input (e.g. `issue(handle)` → `fix/issue-42`) or slug arbitrary text (`fromText`),
  and a **prompt-shortening** one condenses a free-form prompt via the leading
  model's cheap variant (`llm.cheap`, R31) then slugs it. **All** free text — author
  titles and untrusted LLM output alike — routes through `slug`, which strips
  leading/trailing hyphens, forces a leading alphanumeric, caps length, and falls
  back to `flow-<shorthash>` on empty: so a branch name can never begin with `-`
  (CLI-flag/argument injection into `git`/`gh`) nor be empty. A non-deterministic name is
  computed once and recorded, never recomputed on resume; a deterministic name needs
  no read-back.
- **R3** — The starting branch is recorded and restored on **successful** exit. On
  **failure** the flow stays on the feature branch, so a re-run resumes in place —
  HEAD is already on the right branch and the committed log is in the working tree.
- **R4** — On flow start a dirty working tree is stashed, with a user-visible
  warning, so the flow begins clean.
- **R5** — On **successful** exit the progress-log file is removed in a final
  commit, and a feature branch left with no changes other than the progress log is
  deleted (throwaway-branch cleanup). On a **failure** exit the feature branch and
  its committed log are kept intact so the next run can resume; only the failed
  stage's uncommitted partial edits are discarded (it re-runs on resume).
- **R6** — Push and PR creation are flow-controlled and usable at any point; the
  runtime imposes no single terminal push.
- **R30** — On startup the runtime cross-checks the header's recorded branch against
  the working state. If a progress log is found on a branch *other* than the one it
  records — e.g. an in-progress branch was merged, carrying the log into another
  branch — the run aborts with a clear message rather than resuming against the
  wrong branch.
- **R31** — The leading model is named by a `leadModel` **selector** argument to
  `flow(...)` — `FlowContext => LlmTool[?]`, defaulting to `_.claude`. The only way
  to name a model is an accessor on the flow context (`_.claude`, `_.codex`, …),
  which isn't in scope at the `flow(...)` argument position, so the selector defers
  resolution until the context is built. `flow(OrcaArgs(args))` runs against claude;
  `flow(OrcaArgs(args), _.codex)` against codex. The resolved model is exposed in the
  body as `ctx.llm` (used by the runtime for branch naming and default commit
  messages, and available to scripts that need the leading model directly; example
  bodies normally call the concrete accessor `claude`). `llm.session`,
  `reviewAndFixLoop(coder = llm, …)`, and other session-typed calls retain
  backend correlation (R27). `LlmTool` gains a `cheap` method
  returning the backend's cheap variant (claude → haiku, gemini → flash, codex →
  mini); orca uses `llm.cheap` for branch naming and default commit messages. There
  is no implicit default model.
- **R32** — The progress header is **untrusted input** on load (the log is
  human-visible and pushable — R26 — so it may be edited). Before any destructive
  action the runtime validates it: `branch`/`startingBranch` must be safe refs
  (`slug` rules), `promptHash` must equal the recomputed prompt hash, and it refuses
  to `checkout`, `reset --hard`, or delete a protected branch (the default branch /
  `main` / `master`) or any branch outside the orca naming scheme.

**Design.**

```scala
def flow(
    args: OrcaArgs,
    leadModel: FlowContext => LlmTool[?] = _.claude,  // leading-model selector (R31)
    //   ^ resolved against the built context: `_.claude` (default), `_.codex`, …
    // … existing tool overrides …
    branchNaming: Option[BranchNamingStrategy] = None,
    //   ^ None ⇒ slug the prompt; or Some(BranchNamingStrategy.issue(handle)) for issue flows
    progressStore: Option[ProgressStore] = None     // §2.4; pluggable path + format
)(body: FlowControl ?=> Unit): Unit
```

Per R31, `leadModel` is a selector resolved against the built `FlowContext` — the
only way to name a model is an accessor on the context, which isn't in scope at the
`flow(...)` argument position, so resolution is deferred. The resolved model is
`ctx.llm`; `llm.cheap` drives branch naming and default commit messages (overridable
per stage via `commitMessage`, §2.1). The lifecycle therefore builds the context (and
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
   strategies are pure, the prompt-shortening one calls `llm.cheap`. Branch naming is
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
   (which restores the last committed log) — **not** `git clean -x .orca/`, or a log
   force-added but not yet committed in the crash window would be wiped. They re-run
   on resume. **Stay on the feature branch** (R3); do not return to the starting
   branch — HEAD is then already where the next run resumes and the committed log is
   in the working tree, so resume needs no branch lookup. The user switches back to
   the starting branch themselves once they've dealt with the failure.

Setup's own git and LLM operations (branch naming, header commit) run with
runtime-supplied `InStage` — the runtime is the privileged constructor of the
token, so it can mutate during setup before any user stage exists. Push and PR
creation remain ordinary stage actions (R6). The leading model (`llm`) and the
resolved branch are exposed through `FlowContext` accessors. The branch-naming
strategy and the progress store are overridable (R21).

### 2.6 Sessions

**Requirements.**
- **R22** — A stage result may carry an LLM `SessionId`, persisted in the log (with
  the client→server map for server-id backends). On resume, whether the *live*
  backend conversation can be continued is decided by a **non-destructive existence
  probe** (`LlmBackend.sessionExists(id)`) rather than guessed: most backends expose
  one (an on-disk session file, a list command, or a `GET`), pi does not. If the
  probe is absent or says gone, resume falls back to re-seed (R23).
- **R23** — A session is obtained via a get-or-create (`llm.session`) whose id is
  recorded in the log, so a retry reuses it rather than minting a second. A flow
  attaches a `seed` — the essential context to rebuild the agent if its backend
  conversation is lost (typically the plan brief, or the issue/plan when there is no
  brief); the runtime additionally prepends a progress preamble derived from the log
  (which stages have completed). **Re-seed is the reliable, uniform path**: on resume
  orca re-mints and primes the session with preamble + seed unless a backend existence
  probe (R22) confirms the recorded session is still live, in which case it continues
  it.

**Design.**

A flow obtains a session via `llm.session(...)` — a *get-or-create* keyed by the
log, not a plain `new`. It is **pure**: it reserves a session id (a UUID) and records
the id + seed in the log; the backend conversation is created lazily on the first
gated `run`. So `llm.session(...)` is callable outside a stage while the actual LLM
effect stays inside one (R15). On resume it returns the recorded id. Naming it
`session` rather than `newSession` reflects the upsert: a retry does not create a
second session.

```scala
val session = llm.session(seed = plan.brief)   // author's essential context (a String)
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
per backend — `LlmBackend.sessionExists(id)` — not by attempting a resume and
catching an error (which some CLIs don't reliably raise). The recorded id (and, for
server-id backends, the client→server map) is persisted in the log and rehydrated,
then probed:

| Backend  | Probe                                                                   |
|----------|-------------------------------------------------------------------------|
| claude   | on-disk `~/.claude/projects/<cwd-slug>/<id>.jsonl` (cwd-scoped; 30-day prune) |
| codex    | on-disk `find ~/.codex/sessions -name "rollout-*-<id>.jsonl"` (global)   |
| gemini   | `gemini --list-sessions` (project-scoped)                               |
| opencode | `GET /session/<id>` → 200 vs 404 (durable across server restarts)        |
| pi       | none — sessions live in a `deleteOnExit` temp dir, gone across runs      |

If the probe confirms the session, orca continues it; if it returns absent (or the
backend has no probe — pi), it re-mints and re-seeds. **Re-seed stays the guaranteed
fallback**; the probe only makes "continue the live session" a deterministic decision
rather than a hope. *Implementation notes:* this needs (a) the persisted id/map —
today's `SessionRegistry` is in-memory and not rehydrated from the log; and (b) a
`sessionExists` method on the backend SPI. The probe mechanisms are verified against
current CLIs (claude/codex on-machine; the rest from docs/source); gemini's
list output and opencode's directory-scoping should be pinned when the probes land.

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
  limitation (§5) rather than rewriting history on every push.

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

flow(OrcaArgs(args)):                                    // leadModel defaults to `_.claude`
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, claude).value       // Plan (always briefed; has JsonData)

  // Get-or-create the implementer session (pure: id reserved, backend created on
  // first use). The seed (plan brief) primes it on first use, and is replayed if the
  // backend session is lost on resume.
  val session = claude.session(seed = plan.brief)

  for task <- plan.tasks do
    stage(s"task: ${task.title}"):                       // skipped on resume if already done
      claude.runSeeded(task.description, session)
      reviewAndFixLoop(                                   // runs under this stage (using InStage)
        coder = claude, sessionId = session,
        reviewers = allReviewers(claude),
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value,
        formatCommand = Some("cargo fmt")
      )
      // one commit per task: code + progress entry
```

`git.commit`, `claude.runSeeded`, and `reviewAndFixLoop` require `InStage`,
supplied by the enclosing `stage`. There is no plan markdown file — the progress
log subsumes it, which also removes any checkbox state to keep in sync (a
human-readable checklist, if wanted, is cosmetic — §2.8).

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

flow(orcaArgs, branchNaming = Some(BranchNamingStrategy.issue(issueHandle))):  // claude default
  val issue   = gh.readIssue(issueHandle)                            // read
  val session = claude.session(seed = issue.body)                    // get-or-create
  val triage  = stage("Triage"):                                     // LLM → staged
    Plan.autonomous.triage(report(issue), claude).value

  triage match
    case Triage.NotABug(why) =>
      stage("Comment: not a bug")(gh.writeComment(issueHandle, why)) // throwaway branch, deleted on exit

    case Triage.Untestable(_, steps) =>
      stage("Comment: repro steps")(gh.writeComment(issueHandle, steps))

    case Triage.Testable(summary, _, failingTestPath) =>
      stage("Write failing test"):                                   // commits the test
        claude.runSeeded(s"Write the failing test at $failingTestPath …", session)

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
  `List` / small tuples / `SessionId[B]`; a sum codec for the sealed `PlanLike`. No
  new typeclass. Self-contained; lands first.
- **B — Capabilities + tool gating.** `FlowControl`, `InStage`; `(using InStage)` on
  the §2.2 methods (plus `gh.upsertComment` and `createPr` reuse); thread `InStage`
  through side-effecting helpers, `FlowControl` through stage-starting ones. The
  widest, compiler-guided change. Negative compile tests: `git.commit` outside a
  stage, and `stage` inside a fork, must fail to compile.
- **C — Progress log model + store.** `ProgressHeader`/`StageEntry`/`ProgressLog`;
  `ProgressStore` default at `.orca/progress-<hash>.json`; recovery + untrusted-header
  validation (R32).
- **D — Stage runtime + resumption.** `stage`/`display`/`fail`; decode-or-rerun;
  per-stage commit; the `sessionExists` probe on the backend SPI and a persistable
  `SessionRegistry`; `llm.session` / `llm.cheap`; re-seed on resume.
- **E — Flow lifecycle + config.** `flow(...)` setup/teardown, `FlowControl`
  provision, `BranchNamingStrategy`, mandatory leading `llm`.
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
- **Capabilities are leaky pre-capture-checking.** Both `InStage` (no mutation
  outside a stage) and `FlowControl` (no stage inside a fork) prove *lexical*
  enclosure, not the real invariant: a token can be captured and used where it
  shouldn't (an `InStage` smuggled out of a stage; a `FlowControl` closed over inside
  a `parallel` fork). They are kept for the compile-time signal — guard-rails, not
  yet guarantees — and both are future-proofable via Scala 3 capture checking (§6).
  Gating is also the widest change in the project (four tool traits, ~8 helpers,
  every flow script); acceptable here because breaking changes are fine at this stage.
- **Resume is decode-safe, not meaning-safe.** A stored result that no longer decodes
  to the call-site type re-runs the stage; but a stage whose *meaning* changes under
  a stable name and a still-decodable type silently reuses the stale value — the more
  likely edit between a crash and a resume. Relatedly, inserting a *same-named* stage
  before existing ones shifts later occurrence-indices (R10), so their stored entries
  no longer match and that work re-runs — harmless but worth knowing.
- **Resume preserves files, not agent context.** File state is committed and
  restored; the LLM conversation is not. After a crash the re-seed blob restores
  only the seed (e.g. the plan), so a long implementer session resumes materially
  "dumber" — the seed must carry enough for the remaining tasks to stand alone.
- **Cross-backend live-session resume is non-uniform.** A non-destructive existence
  probe exists for claude/codex/gemini/opencode (on-disk file, `--list-sessions`,
  `GET /session/<id>`) but **not pi** (`deleteOnExit` temp dir), so pi always
  re-seeds. Even where a probe exists it needs the persisted id/map (today's
  `SessionRegistry` is in-memory) and carries caveats — claude is cwd-scoped + 30-day
  pruned, gemini's id↔file is fuzzy (use the list, not the file), opencode is
  project-scoped. So live continuation is a per-backend optimization gated on the
  probe, and **re-seed remains the path that holds on every backend** (R22/R23/§2.6).
- **Branch naming: only the prompt-shortening strategy is non-deterministic.** The
  deterministic strategies (`issue(handle)` → `fix/issue-42`, `fromText`) are
  recoverable by recomputation; the prompt-shortening one (for free-form `implement`
  prompts) is why the header persists the once-computed name for read-back on resume.
- **`JsonData` coverage.** Sealed `PlanLike` needs jsoniter sum-type config;
  primitives/tuples/`SessionId` need hand-written `JsonData` givens; codecs must be
  lossless (R9).
- **Custom external effects.** Built-in idempotency covers `createPr` /
  `upsertComment` (R24); a flow's own irreversible side effect must be made
  idempotent by its author or it repeats on resume.
- **Stage ordering discipline.** Push-after-commit (§3.2) and no-concurrent-stages
  (R12) are authoring rules the compiler cannot enforce.

---

## 6. Out of scope & future work

Deliberately deferred; recorded here so nothing is lost:

- **Capture-checking migration.** Turn the convention guard-rails into compile-time
  guarantees: mark `InStage` and `FlowControl` as `caps.Capability` and type the
  escaping positions (stage bodies, fork thunks) as pure, so a smuggled `InStage` or
  a `FlowControl` captured into a fork is a compile error. Additive — no call-site
  changes (§2.2, §5).
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
