# Stage-Bound Flow Runtime — Implementation Plan

> **For agentic workers:** use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement task-by-task. Steps use `- [ ]` for
> tracking.
>
> **No-code planning note:** per project constraint this plan describes each test
> (its assertion) and each implementation (its behaviour + signatures) rather than
> spelling out code bodies. Engineers write the actual Scala at execution time,
> following the cited signatures and **ADR 0018** (`adr/0018-stage-bound-flow-runtime.md`),
> the complete design record.

**Goal:** Make the **stage** the universal unit of resumable, committing work so an
interruption mid-run costs at most the in-progress stage.

**Architecture:** Per ADR 0018 (`adr/0018-stage-bound-flow-runtime.md`). A `flow` binds one feature
branch + one JSON progress log; `stage` commits code + a log entry atomically and is
skipped on resume; capabilities `FlowContext` ⊃ `FlowControl` (start a stage) +
`InStage` (mutate) gate effects; sessions resume via a per-backend existence probe,
else re-seed.

**Tech Stack:** Scala 3 (LTS), Ox, jsoniter-scala, tapir Schema, os-lib, munit;
backends shell out via `CliRunner`/`PipedCliProcess`.

## Global Constraints

- JDK 21+; Scala 3 LTS; build/test via `sbt` (use `sbt --client` against a running
  server when iterating). Tests are munit.
- Zero-warning build: `-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`.
- Braceless Scala; explicit public return types; immutable data; capabilities via
  `using`; no class-level `var`.
- Backward compatibility is **not** a goal — breaking tool signatures and flow
  scripts is allowed.
- The on-disk format is JSON via jsoniter (R28); everything type-safe (R27).
- Reference: requirements **R1–R32** and §-numbers are in ADR 0018
  (`adr/0018-stage-bound-flow-runtime.md`).

## File map (where responsibilities land)

- `tools/src/main/scala/orca/llm/JsonData.scala` — add primitive/collection/`SessionId`
  givens (Epic A).
- `flow/src/main/scala/orca/Capabilities.scala` *(new)* — `FlowContext` stays in
  `FlowContext.scala`; add `trait FlowControl extends FlowContext` and
  `opaque type InStage` here (Epic B).
- `tools/src/main/scala/orca/tools/{GitTool,FsTool,GitHubTool}.scala`,
  `tools/src/main/scala/orca/llm/{LlmTool,LlmCall}.scala` + `AutonomousTextCall` —
  add `(using InStage)` to mutators; `gh.upsertComment`; `createPr` reuse (Epic B).
- `flow/src/main/scala/orca/progress/{ProgressLog,ProgressStore}.scala` *(new
  package `orca.progress`)* — log model + store (Epic C).
- `flow/src/main/scala/orca/Flow.scala` — rewrite `stage`/`display`/`fail`; resume
  (Epic D).
- `tools/src/main/scala/orca/backend/{LlmBackend,SessionRegistry}.scala` + each
  backend module — `sessionExists`, registry persistence (Epic D).
- `tools/src/main/scala/orca/llm/LlmTool.scala` — `cheap`, `session` (Epic D/E).
- `runner/src/main/scala/orca/flow.scala`, `runner/.../DefaultFlowContext.scala`,
  `flow/src/main/scala/orca/BranchNamingStrategy.scala` *(new)* — lifecycle + config
  (Epic E).
- `flow/src/main/scala/orca/plan/Plan.scala` — retire recovery; always-briefed
  (Epic F). `examples/*.sc` — convert (Epic F).
- `README.md` / docs (Epic G).

---

## Epic A — `JsonData` for stage results

*Goal:* every value a stage returns has a `JsonData` instance. Self-contained; lands
first. (R9, R28; §2.3.)

### Task A1: `JsonData` givens for primitives, `Unit`, `Option`, `List`, small tuples

**Files:**
- Modify: `tools/src/main/scala/orca/llm/JsonData.scala`
- Test: `tools/src/test/scala/orca/llm/JsonDataGivensTest.scala` *(new)*

**Interfaces:**
- Produces: `given JsonData[String|Int|Long|Boolean|Double|Unit]`,
  `given [A: JsonData]: JsonData[Option[A]]`, `…[List[A]]`, `…[(A, B)]` (and the
  arities flows use). Each wraps a jsoniter `JsonValueCodec` + a tapir `Schema`.

- [ ] **Step 1 — failing test:** assert `summon[JsonData[Int]]` (and each primitive,
  `Unit`, `Option[String]`, `List[Int]`, `(Int, String)`) round-trips a value
  through `encode`→`decode` to an equal value (R9 lossless).
- [ ] **Step 2 — run, expect FAIL** (`sbt tools/testOnly *JsonDataGivensTest`):
  no given instance.
- [ ] **Step 3 — implement:** add the givens; reuse jsoniter core codecs for
  primitives, derive collection/tuple codecs; `Schema` from tapir's built-ins.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit** (`feat(json): JsonData givens for stage-result primitives`).

### Task A2: `JsonData[SessionId[B]]` and the sealed `PlanLike` sum codec

**Files:**
- Modify: `tools/src/main/scala/orca/llm/JsonData.scala`,
  `flow/src/main/scala/orca/plan/Plan.scala`
- Test: `flow/src/test/scala/orca/plan/PlanJsonDataTest.scala` *(new)*

**Interfaces:**
- Consumes: A1 givens. Produces: `given [B]: JsonData[SessionId[B]]` (opaque alias
  over `String`); `given JsonData[PlanLike]` (jsoniter sum config over
  `Plan`/`PlanWithBrief`).

- [ ] **Step 1 — failing test:** round-trip a `SessionId[ClaudeCode]`, a `Plan`, and
  a `PlanWithBrief` as `PlanLike` (decode preserves the concrete subtype).
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement:** opaque-type given via the underlying `String` codec;
  configure a jsoniter discriminator for the sealed hierarchy.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit** (`feat(plan): JsonData for SessionId and PlanLike`).

---

## Epic B — Capabilities + tool gating

*Goal:* `FlowControl`/`InStage` exist and every side-effecting tool method requires
`InStage`; pure reads stay ungated; GitHub effects gain idempotency. Largest,
compiler-guided. (R15–R17, R24, R29; §2.2, §2.7.)

### Task B1: capability types

**Files:**
- Create: `flow/src/main/scala/orca/Capabilities.scala`
- Modify: `flow/src/main/scala/orca/FlowContext.scala` (doc only)
- Test: `flow/src/test/scala/orca/CapabilitiesTest.scala` *(new)*

**Interfaces:**
- Produces: `trait FlowControl extends FlowContext`; `opaque type InStage` with a
  `private[orca]` constructor (e.g. `InStage.unsafe`). No public constructors.

- [ ] **Step 1 — failing test:** a `FlowControl` is usable where `using FlowContext`
  is required (subtyping); `InStage` cannot be constructed from outside `orca`
  (compile-time — a `compileErrors("…")` munit check).
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** the two types + runtime-only `InStage` constructor.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit** (`feat(flow): FlowControl + InStage capabilities`).

### Task B2: gate `GitTool` / `FsTool` mutators with `InStage`

**Files:**
- Modify: `tools/src/main/scala/orca/tools/GitTool.scala`,
  `tools/src/main/scala/orca/tools/FsTool.scala`
- Test: `tools/src/test/scala/orca/tools/GitGatingTest.scala` *(new)*

**Interfaces:**
- Produces: `(using InStage)` on `createBranch`, `checkout*`, `commit`, `push`,
  `addWorktree`, `removeWorktree`, `ensureClean`, `FsTool.write`. Reads
  (`diff`/`log`/`currentBranch`, `fs.read`) unchanged.

- [ ] **Step 1 — failing test:** `git.commit` called without an `InStage` in scope
  is a compile error (`compileErrors`); with one in scope it compiles. A read
  (`git.diff`) compiles without `InStage`.
- [ ] **Step 2 — run, expect FAIL** (methods don't yet require it).
- [ ] **Step 3 — implement:** add the `(using InStage)` clause to each mutator;
  thread the token through `OsGitTool`/`OsFsTool` implementations.
- [ ] **Step 4 — run, expect PASS;** fix fallout in callers within `tools`.
- [ ] **Step 5 — commit** (`feat(tools): gate git/fs mutators with InStage`).

### Task B3: gate `GitHubTool`; add `upsertComment` + idempotent `createPr`

**Files:**
- Modify: `tools/src/main/scala/orca/tools/GitHubTool.scala`
- Test: `tools/src/test/scala/orca/tools/GitHubIdempotencyTest.scala` *(new)*

**Interfaces:**
- Produces: `(using InStage)` on `createPr`/`updatePr`/`writeComment`; new
  `upsertComment(marker: String, body: String)(using InStage)`; `createPr` matches an
  existing open PR on **head + base** and returns it instead of creating a duplicate.

- [ ] **Step 1 — failing test** (stubbed `CliRunner` for `gh`): `createPr` when a PR
  for (head, base) already exists returns that PR and issues no create; `upsertComment`
  edits a prior comment carrying the marker rather than appending; the marker contains
  the prompt hash.
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** the lookup-then-act logic over the `gh` CLI.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit** (`feat(gh): InStage gating + idempotent PR/comment`).

### Task B4: gate LLM call entry points

**Files:**
- Modify: `tools/src/main/scala/orca/llm/LlmTool.scala`,
  `tools/src/main/scala/orca/llm/LlmCall.scala` (+ `AutonomousTextCall`)
- Test: `tools/src/test/scala/orca/llm/LlmGatingTest.scala` *(new)*

**Interfaces:**
- Produces: `(using InStage)` on every `LlmCall`/`AutonomousTextCall` run entry point
  and `LlmTool.ask`.

- [ ] **Step 1 — failing test:** an autonomous `run` without `InStage` is a compile
  error; with one it compiles.
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement:** add the clause; thread through `DefaultLlmCall`.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit** (`feat(llm): gate LLM calls with InStage`).

### Task B5: thread `InStage` through side-effecting helpers

**Files:**
- Modify: `flow/src/main/scala/orca/review/ReviewLoop.scala`,
  `flow/src/main/scala/orca/pr/summarisePr.scala`,
  `flow/src/main/scala/orca/plan/Plan.scala` (generation paths),
  `flow/src/main/scala/orca/review/*` (fixLoop, lint, reviewer fan-out)
- Test: existing helper tests recompiled; add `compileErrors` guard where useful.

**Interfaces:**
- Produces: `reviewAndFixLoop`, `fixLoop`, `lint`, `summarisePr`, `Plan.autonomous.*`
  take `(using InStage)`; they call `stage`-gated tools but do **not** open stages.

- [ ] **Step 1 — failing test/build:** these helpers don't compile after B2–B4 (they
  call gated methods without `InStage`).
- [ ] **Step 2 — run `sbt flow/compile`, expect FAIL.**
- [ ] **Step 3 — implement:** add `(using InStage)` to each helper signature and
  forward it.
- [ ] **Step 4 — run, expect PASS;** `sbt test` for the affected modules green.
- [ ] **Step 5 — commit** (`refactor: thread InStage through side-effecting helpers`).

---

## Epic C — Progress log model + store

*Goal:* the JSON log, its store, and recovery primitives, testable without a flow.
(R18–R21, R30; §2.4.)

### Task C1: log model + JSON codecs

**Files:**
- Create: `flow/src/main/scala/orca/progress/ProgressLog.scala`
- Test: `flow/src/test/scala/orca/progress/ProgressLogTest.scala` *(new)*

**Interfaces:**
- Produces: `case class ProgressHeader(startingBranch, branch, promptHash: String)`,
  `case class StageEntry(id, name, resultJson: String)`,
  `case class ProgressLog(header, entries: List[StageEntry])`, all `derives JsonData`.

- [ ] **Step 1 — failing test:** a `ProgressLog` round-trips through JSON unchanged.
- [ ] **Step 2 — run, expect FAIL.** **Step 3 — implement** the case classes + codec.
  **Step 4 — PASS. Step 5 — commit** (`feat(progress): log model`).

### Task C2: `ProgressStore` (default OS-backed) + upsert + path

**Files:**
- Modify: `flow/src/main/scala/orca/progress/ProgressStore.scala` *(new)*
- Test: `flow/src/test/scala/orca/progress/ProgressStoreTest.scala` *(new)*

**Interfaces:**
- Produces: `trait ProgressStore { load(): Option[ProgressLog];
  writeHeader(h)(using InStage); appendEntry(e)(using InStage) }`;
  `ProgressStore.default` → JSON at `.orca/progress-<hash>.json`, `hash` = first 12
  hex of `SHA-256(userPrompt)` (reuse `Plan.hashUserPrompt`).

- [ ] **Step 1 — failing test** (temp dir): `writeHeader` then `appendEntry` twice
  with the same id keeps one entry (upsert, last wins); `load` after returns the log;
  a wholly-malformed file → `load` returns `None` (treat as no log).
- [ ] **Step 2 — FAIL. Step 3 — implement** (os-lib read/write; tolerate parse
  errors as `None`). **Step 4 — PASS. Step 5 — commit** (`feat(progress): store`).

### Task C3: recovery primitive (snapshot-before-stash + header validation)

**Files:**
- Modify: `flow/src/main/scala/orca/progress/ProgressStore.scala`
- Test: `flow/src/test/scala/orca/progress/RecoveryTest.scala` *(new)* (temp git repo)

**Interfaces:**
- Produces: a `recover(workDir, userPrompt)(using InStage): Option[ProgressLog]` that
  snapshots the log, `ensureClean`-stashes, restores the snapshot if the stash removed
  it, reads + **validates** the header (refs are slug-safe; `promptHash` matches;
  refuse protected branches — R32), checks out `header.branch`, and aborts on a
  branch/header mismatch (R30).

- [ ] **Step 1 — failing tests:** (a) recovery on a temp repo with a committed log
  checks out the recorded branch; (b) a header naming a protected branch (`main`) is
  rejected; (c) a `promptHash` mismatch aborts; (d) finding the log on a
  non-matching branch aborts with a clear message.
- [ ] **Step 2 — FAIL. Step 3 — implement** the dance + validation. **Step 4 — PASS.
  Step 5 — commit** (`feat(progress): recovery with untrusted-header validation`).

---

## Epic D — Stage runtime + resumption + sessions

*Goal:* `stage`/`display`/`fail`, decode-or-rerun resume, per-stage commit, and the
session model (existence probe + re-seed). Needs A, B, C. (R7–R14, R22, R23; §2.1,
§2.6.)

### Task D1: `sessionExists` on the backend SPI + per-backend probes

**Files:**
- Modify: `tools/src/main/scala/orca/backend/LlmBackend.scala`; each backend's
  `*Backend.scala` (claude/codex/gemini/opencode/pi)
- Test: per-backend unit tests with stubbed `CliRunner`/HTTP + a temp session store

**Interfaces:**
- Produces: `def sessionExists(id: SessionId[B]): Boolean` on `LlmBackend`. claude →
  `~/.claude/projects/<cwd-slug>/<id>.jsonl` exists; codex → `find ~/.codex/sessions
  -name rollout-*-<id>.jsonl`; gemini → parse `gemini --list-sessions`; opencode →
  `GET /session/<id>` 200 vs 404; pi → always `false`.

- [ ] **Step 1 — failing tests:** for each backend, a known-present id → `true`, an
  absent id → `false`; pi → always `false`.
- [ ] **Step 2 — FAIL. Step 3 — implement** each probe (filesystem/HTTP/CLI per the
  table). **Step 4 — PASS** (gate gemini/opencode live checks behind
  `ORCA_INTEGRATION`). **Step 5 — commit** (`feat(backend): sessionExists probes`).

### Task D2: persist + rehydrate `SessionRegistry`

**Files:**
- Modify: `tools/src/main/scala/orca/backend/SessionRegistry.scala`
- Test: `tools/src/test/scala/orca/backend/SessionRegistryPersistTest.scala` *(new)*

**Interfaces:**
- Produces: registry can serialise its id (+ client→server map) to/from a
  `ProgressLog`-carried structure so a fresh process sees a recorded session as
  *Resume*, not *Fresh*.

- [ ] **Step 1 — failing test:** dump a registry to JSON, reload into a new instance,
  a recorded client id dispatches `Resume` with the recorded server id.
- [ ] **Step 2 — FAIL. Step 3 — implement** dump/load. **Step 4 — PASS. Step 5 —
  commit** (`feat(backend): persistable SessionRegistry`).

### Task D3: `llm.session(seed)` get-or-create + `llm.cheap`

**Files:**
- Modify: `tools/src/main/scala/orca/llm/LlmTool.scala` + per-backend tool impls
- Test: `tools/src/test/scala/orca/llm/SessionApiTest.scala` *(new)*

**Interfaces:**
- Produces: `def cheap: LlmTool[B]` (claude→haiku, gemini→flash, codex→mini, …);
  `def session(seed: => String): SessionId[B]` — **pure** (reserves an id, records id
  + seed; no backend call). The seed is applied on first `run` and re-applied after a
  lost-session re-mint with the progress preamble prepended.

- [ ] **Step 1 — failing test:** `session` is callable without `InStage` (pure);
  returns a stable id within a run; `cheap` returns the backend's cheap variant.
- [ ] **Step 2 — FAIL. Step 3 — implement.** **Step 4 — PASS. Step 5 — commit**
  (`feat(llm): pure session get-or-create + cheap`).

### Task D4: rewrite `stage` (+ `display`, keep `fail`) with commit + resume

**Files:**
- Modify: `flow/src/main/scala/orca/Flow.scala`
- Test: `flow/src/test/scala/orca/StageRuntimeTest.scala` *(new)*, using a
  `TestFlowContext` providing `FlowControl` + a temp-dir `ProgressStore` + stub git.

**Interfaces:**
- Consumes: A (`JsonData`), B (`FlowControl`/`InStage`), C (`ProgressStore`).
- Produces: `def stage[T: JsonData](name, commitMessage: Option[T => String] = None)
  (body: InStage ?=> T)(using FlowControl): T`; `display(msg)(using FlowContext)`;
  `fail(msg)(using FlowContext): Nothing`.

- [ ] **Step 1 — failing tests:** (a) a stage records its result and makes one commit
  (code delta + log entry, force-added); (b) **run-twice** returns the stored value
  without re-running the body; (c) an undecodable stored entry re-runs; (d) a stage
  in a child thread (a fork) cannot be opened — `FlowControl` not in scope
  (`compileErrors`); (e) nested stages each commit; (f) the commit message uses
  `commitMessage(result)` when given, else `llm.cheap`.
- [ ] **Step 2 — FAIL. Step 3 — implement** the control flow (resume check → run →
  record+commit) per §2.1. **Step 4 — PASS. Step 5 — commit**
  (`feat(flow): resumable committing stages`).

### Task D5: session re-seed on resume (probe → continue or re-seed)

**Files:**
- Modify: `flow/src/main/scala/orca/Flow.scala` (session-aware run path) / `LlmTool`
- Test: `flow/src/test/scala/orca/SessionResumeTest.scala` *(new)*

**Interfaces:**
- Produces: on first `run` after `session`, the seed is prepended; on resume, if
  `sessionExists` is false (or pi), the session is re-minted and primed with
  preamble + seed; if true, it continues.

- [ ] **Step 1 — failing tests** (stub backend): live id → continues, no re-seed;
  absent id → re-mints and the first prompt carries preamble + seed; pi → always
  re-seeds.
- [ ] **Step 2 — FAIL. Step 3 — implement.** **Step 4 — PASS. Step 5 — commit**
  (`feat(flow): existence-probed session resume + re-seed`).

---

## Epic E — Flow lifecycle + config

*Goal:* `flow(...)` provides `FlowControl`, owns setup/teardown, branch naming, and
the leading model. Needs B, C, D. (R1–R6, R31; §2.5.)

### Task E1: `BranchNamingStrategy` + safe `slug`

**Files:**
- Create: `flow/src/main/scala/orca/BranchNamingStrategy.scala`
- Test: `flow/src/test/scala/orca/BranchNamingTest.scala` *(new)*

**Interfaces:**
- Produces: `slug(text, maxLen=50): String`; `issue(handle, prefix="fix")`;
  `fromText(text)`; `shortenPrompt` (uses `llm.cheap`).

- [ ] **Step 1 — failing tests:** `slug` lower-cases, keeps `[a-z0-9-]`, strips
  leading/trailing `-`, never returns a leading `-` or empty (empty input →
  `flow-<shorthash>`), caps length; `issue(handle)` → `fix/issue-<n>`.
- [ ] **Step 2 — FAIL. Step 3 — implement.** **Step 4 — PASS. Step 5 — commit**
  (`feat(flow): injection-safe branch naming`).

### Task E2: `flow(...)` setup/teardown + `FlowControl` provision + accessors

**Files:**
- Modify: `runner/src/main/scala/orca/flow.scala`,
  `runner/src/main/scala/orca/runner/DefaultFlowContext.scala`,
  `flow/src/main/scala/orca/accessors.scala`
- Test: `runner/src/test/scala/orca/FlowLifecycleTest.scala` *(new)* (temp git repo,
  stub tools)

**Interfaces:**
- Produces: `def flow[B <: BackendTag](args, llm: LlmTool[B], …overrides,
  branchNaming = shortenPrompt, progressStore = default)(body: FlowControl ?=> Unit)`;
  `DefaultFlowContext` implements `FlowControl`; `llm` accessor returns `LlmTool[B]`.

- [ ] **Step 1 — failing tests:** setup records starting branch, stashes a dirty tree
  (warns), creates the feature branch, commits the header; on success teardown removes
  the log, deletes a code-change-free branch, returns to start; on failure HEAD stays
  on the feature branch; a body calling `stage` compiles (FlowControl in scope).
- [ ] **Step 2 — FAIL. Step 3 — implement** the lifecycle per §2.5 (branch naming as
  a setup step; runtime-supplied `InStage` for setup git ops). **Step 4 — PASS.
  Step 5 — commit** (`feat(flow): branch+log lifecycle, FlowControl provision`).

---

## Epic F — Replace `Plan` persistence; migrate examples

*Goal:* stage log is the sole resume mechanism; `Plan` always briefed; examples
converted. Needs D, E. (R25; §2.8.)

### Task F1: retire `Plan.recover`; always-briefed plans; task-loop on stages

**Files:**
- Modify: `flow/src/main/scala/orca/plan/Plan.scala` (+ `PlanPrompts`, `Sessioned`)
- Test: update `flow/src/test/scala/orca/plan/PersistentPlanTest.scala` (now removed
  behaviour) + new `flow/src/test/scala/orca/plan/AlwaysBriefedTest.scala`

**Interfaces:**
- Produces: removal of `recover`/`recoverOrCreate`/`.orca/plan-<hash>.md`/checkbox
  rendering; `Plan.autonomous.from`/`interactive.from` return a briefed plan;
  `implementTaskLoop(plan)(body)` = `for task <- plan.tasks do stage(s"task:
  ${task.title}")(body(task))`.

- [ ] **Step 1 — failing tests:** `from(...)` yields a plan exposing `.brief`; the
  task loop produces one `task: <title>` commit per task via `stage`; the old
  markdown-file APIs no longer exist (compile check / removed tests).
- [ ] **Step 2 — FAIL. Step 3 — implement** the removals + always-brief +
  stage-based loop. **Step 4 — PASS** (`sbt test`). **Step 5 — commit**
  (`refactor(plan): stage-based task loop, always-briefed, drop recover`).

### Task F2: convert example flows

**Files:**
- Modify: `examples/implement.sc`, `epic.sc`, `issue-pr.sc`, `issue-pr-bugfix.sc`,
  `implement-interactive.sc`

**Interfaces:** Consumes the new `flow(args, llm)`, `stage`, `llm.session`,
`BranchNamingStrategy`, idempotent `gh`. Follow ADR 0018 §3.

- [ ] **Step 1 — convert** each script to the new shape (leading model arg; reads
  outside stages; `stage` per side-effecting step; `issue(...)` naming for issue
  flows; push as a later stage).
- [ ] **Step 2 — verify:** the scala-cli smoke test (publishLocal + run a minimal
  converted script against a stub backend) is green.
- [ ] **Step 3 — commit** (`docs(examples): convert flows to stage runtime`).

---

## Epic G — User documentation

*Goal:* document what the compiler can't teach. Runs alongside; each epic contributes
its slice.

### Task G1: README / user-guide section

**Files:** Modify: `README.md` (+ `design.md` cross-link).

- [ ] **Step 1 — write** the user-facing guide: the capability model
  (`FlowContext`/`FlowControl`/`InStage`), the authoring rules (push-after-commit,
  no concurrent stages, reads-outside-stages), resume semantics + seed/re-seed, and
  the accepted limitations (open-PR log + confidentiality, per-backend session
  caveats).
- [ ] **Step 2 — commit** (`docs: stage-bound flow runtime guide`).

---

## Dependency order

```
A ─┐
B ─┼──> D ──> E ──> F ──> G
C ─┘        (A,B,C feed D; B,C,D feed E; D,E feed F; G alongside)
```

A, B, C are independent and can proceed in parallel. D needs all three. E needs
B+C+D. F needs D+E. G is written incrementally with each epic.

## Self-review (ADR 0018 coverage)

- R7–R14 → Epic D (D4). R15–R17, R29 → Epic B (B1–B5). R9, R27, R28 → Epic A + C1.
- R18–R21, R30 → Epic C. R1–R6, R31 → Epic E. R22–R23 → D1–D3, D5. R24, R26 → B3 +
  Epic G (the open-PR wart is documented, not coded). R25 → Epic F. R32 → C3.
- Accepted limitations (§5) → surfaced in Epic G; no code.
- Open items: the capture-checking migration (mark capabilities `caps.Capability`) is
  **out of scope** for this plan — it's the future tightening noted in §2.2/§5; the
  capabilities ship as convention-enforced now.
