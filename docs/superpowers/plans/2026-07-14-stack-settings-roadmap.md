# Stack Settings (ADR 0019) — Implementation Roadmap

> **For agentic workers:** this is the epic-level roadmap. Each epic gets its
> own detailed plan (`2026-07-14-stack-settings-epic-N.md`) written just
> before that epic starts, following superpowers:writing-plans. Execute
> epics strictly in order; after each epic, run the multi-agent review gate
> before starting the next.

**Goal:** implement ADR 0019 — per-project stack settings
(`.orca/settings.properties`) with agent-based auto-discovery, the `.orca/`
committed-by-default flip, and `Configured`-based defaults in
`reviewAndFixLoop`.

**Spec:** `adr/0019-project-stack-settings.md` (Accepted). The ADR is the
source of truth; this roadmap only sequences it.

**Branch:** `stack-settings`. One implementer at a time; sbt-running
subagents run in background. Scala work loads the direct-style-scala skill.

## Review gate (after every epic)

All tests green, then parallel review agents over the epic's diff:
code-functionality, code-structure, readability, scala-fp, simplicity,
test-reviewer (plus security-reviewer for epics 1 and 4 — they touch
git/subprocess surfaces). Apply findings before the next epic.

## Epics

### Epic 1 — `.orca/` layout flip: OrcaDir, cache/, lock move, lint spill
Independent of settings; everything later writes into this layout.
1. `OrcaDir` helper (tools): creates `.orca/` + `cache/` idempotently,
   writes `cache/.gitignore` (`# Automatically created by orca.` + `*`) and
   `CACHEDIR.TAG` (spec signature line) first; owns path constants incl.
   `settings.properties`.
2. `FlowLock` → `.orca/cache/flow.lock` via OrcaDir; delete
   `excludeLockFromGit` (info/exclude retired).
3. `ProgressStore` creates `.orca` through OrcaDir (path + forceAdd
   unchanged — load-bearing).
4. Lint spill → `{workDir}/.orca/cache/lint-*.txt`; reconcile `os.pwd` →
   `workDir` by adding `def workDir: os.Path` to `FlowContext`.
5. Migration warning at flow start: `git check-ignore` on the settings path
   → `OrcaEvent.Step` naming the exact gitignore line to remove.

Decisions locked: `workDir` accessor on `FlowContext`; `OrcaDir` in the
tools module; stale `info/exclude` lines left behind (ADR: harmless).

### Epic 2 — Settings data layer: `StackSettings`, parser/writer, Lint lists
Pure, unit-testable; pins file format before lifecycle code consumes it.
1. `StackSettings(format, lint, test: List[String])` in flow's `orca`
   package; re-export via `runner/.../exports.scala`.
2. Strict parser: `#` comments, `key = value` (verbatim after first `=`,
   trimmed), repeated keys append, empty value ≡ omitted, unknown key /
   malformed line = error naming valid keys. NOT java.util.Properties.
3. Writer: header comment, per-line evidence comments, commented-out unset
   keys with reasons; round-trips through the parser.
4. `Lint(commands: List[String], agent)`: run each via `bash -c`, label
   each output with command + exit status, concatenate into one summariser
   pass; short-circuit only when every output is empty; spill threshold on
   the concatenation. Update `ReviewLoop` call site + `FlowCompilesTest`
   canaries.

### Epic 3 — Context plumbing: `stackSettings` on FlowContext, flow param, file read
Working intermediate semantics: override > file > empty (discovery arrives
in Epic 4).
1. `FlowContext.stackSettings: StackSettings`; construction stays pure —
   one-shot slot in `DefaultFlowContext` populated by the lifecycle;
   `TestFlowContext` constructor default.
2. `flow(stackSettings: Option[StackSettings] = None)` threaded through
   `runFlow` → `FlowWiring`/`FlowLifecycle.run`.
3. Setup integration: read+validate existing file BEFORE `ensureClean`
   (malformed aborts before tree mutation; the pre-stash read records
   file-existed for Epic 4); override skips the read.
4. Integration tests: committed file, untracked-then-stashed file,
   malformed aborts with no stash, override precedence, flow-param canary.

### Epic 4 — Agent-based discovery + write sequencing (the delicate one)
1. `StackDiscovery` (runner): private result type (per-command evidence,
   per-task unset reason) + the principle-based prompt per the ADR
   (semantic task definitions, survey incl. CI workflows, entry points,
   configured-before-proposed, evidence citations, orca-`.sc` exclusion,
   one fictional-stack example; NO real-stack catalog).
2. Mechanical checks (orca-side, pure): first word after stripping `VAR=`
   prefixes resolves via `bash -c 'command -v -- <word>'` or is an
   executable path in the repo; cited evidence files exist; failures
   demote to commented lines with reason.
3. Lifecycle integration: fresh arm discovers after `ensureClean`, writes
   the file (untracked, never staged) immediately before the header
   commit; resume arm same slot, no commit; no-evidence still writes
   (all-commented) + warning; discovery failure aborts as surfaced setup
   failure (never degrade to an empty written file).
4. Test seam: canned-discovery agent stub; migrate ALL full-flow tests that
   would now hit discovery against the throwing `StubAgent` — one
   convention (settings file in fixture vs override) applied across
   `FlowLifecycleTest`, `OrcaTest`, `OrcaOverridesTest`, `OpencodeFlowTest`, …
5. Integration tests: file lands in branch's first commit; delete-then-
   resume leaves untracked; legacy-ignored repo untracked; failure aborts
   before any stage; event lines match the ADR shape.

### Epic 5 — Consumption: `Configured`, reviewAndFixLoop, examples, docs
1. `Configured[+A]` (`FromSettings`/`Off`/`Use`) in flow's top-level `orca`
   package + export.
2. `reviewAndFixLoop`: `formatCommands: Configured[List[String]]`,
   `lint: Configured[Lint]`; `FromSettings` resolves from
   `ctx.stackSettings` BEFORE the CheckedPar fan-out; empty list ≡ Off;
   lint gate = `Lint(stackSettings.lint, ctx.agent.cheap)`.
3. Migrate review tests/fixtures; cover every arm incl. FromSettings-over-
   empty ≡ no gate.
4. Examples lose stack-specific lines; explicit ones become `Use`/`Off`.
5. README/AGENTS.md: file format, discovery + failure semantics, layout
   flip + migration warning, `Configured` migration table, the
   omitted-lint behavior change.

## Dependency edges

1 ∥ 2 (both independent) → 3 (needs 2's parser/type, 1's paths) →
4 (needs 1+2+3) → 5 (needs 3; meaningful end-to-end after 4).

## Open decisions to resolve at each epic's planning time

- E2: labeled-output framing for the summariser prompt; writer column
  alignment; empty value inside repeated key (drop, per ADR).
- E3: one-shot population mechanism (slot + private setter vs setup return
  value); pre-population access behavior for test doubles.
- E4: header-commit sweep mechanics (`commit`'s add -A scope — verify the
  settings file rides it without its own forceAdd); "executable path inside
  the repo" semantics; `OrcaEvent` case for discovery report lines; fixture
  migration convention.
- E5: `Use(Nil)` ≡ format nothing (yes, consistent); `Configured` package
  (top-level, script-facing).
