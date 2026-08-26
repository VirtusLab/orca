# Review rework — implementation plan

Expands [specification.md](specification.md) into a concrete plan. All paths
are repo-relative. Planned against `master`; file/line references were
verified there. Rebase note: the open `add-keep-changes-cli-flag` PR does not
touch `flow/`, but overlaps on
`runner/src/main/scala/orca/runner/FlowLifecycle.scala` (PR 1 edits it to
record the starting commit) and `README.md`.

## Landing order

**PR 2 → PR 1 → PR 3 → PR 4.**

- PR 2 deletes roughly a third of `ReviewLoop.scala`'s machinery (gate split,
  `GateLedger`, gate folding at every exit, gate lines in every Step). PR 1
  restructures exactly that file (single-round path, whole-run diff
  source); building on plumbing PR 2 deletes would mean doing the loop-exit
  work twice.
- PR 3's wording assumes PR 2 (severity survives; confidence does not), and
  its re-review additions land in a prompt whose only remaining shipped-flow
  consumers after PR 1 are the final loop and `simple.sc`'s kept loop.
- PR 4 is content-independent (selection prompt + reviewer definitions); it
  lands last so the researched checklists are written against the settled
  review shape.

Each PR is complete and green against the master it lands on; PR 1 rebases on
PR 2's rewrite of `ReviewLoop.scala`, PR 3 on both PRs' edits to
`initial-review.md`/`re-review.md`.

---

# PR 2 — Drop the confidence machinery

## Behavior

**Severity stays; confidence goes.** `Severity`
(`flow/src/main/scala/orca/review/Severity.scala`) survives untouched: it
drives display ordering (`review.sc`), the fix prompt's `[Warning]` labels,
and the unresolved-findings block. `Confidence`, `ConfidenceGate`,
`InvalidConfidence`, and `ReviewIssue.confidence` are removed.

**"Report only what should be fixed" replaces the gate at the source.** The
initial-review prompt's `## Confidence` section (lines 16–33 of
`flow/src/main/resources/orca/review/prompts/initial-review.md`) is replaced
by a short section instructing: report a finding only if you believe it
should be fixed; do not report hedges, hunches you didn't verify, or style
opinions; if you verified it, report it regardless of how cheap or expensive
the fix is.

**"Quiet" = reported zero findings.** `narrowingAcrossRounds`
(`flow/src/main/scala/orca/review/ReviewerSelector.scala`) keeps its exact
semantics. `ReviewLoopState.afterRound` records
`ReviewResult(c.gated.kept.map(_.issue))` — the post-gate set
(ReviewLoop.scala:271); with the gate gone, kept == reported, so
`reviewersWithIssues` sees every finding and a reviewer is retired only on
genuine silence. No selector change; pin by a test (a reviewer that reported
anything is re-run next round).

**Declines keep today's semantics; no new damping.** A fixer decline
(`ignored`) is carried to the next round's reviewers as the fixer's position
(`declinedBlock` invites re-reporting with a rebuttal); termination rests on
the clean round, the zero-fixed halt, and the cap. The loop can end with
open disagreements; they are reported at exit in `IgnoredIssues` as today.

**`GateLedger` dies; its title-merge survives.** `GateLedger.mergeLatestByTitle`
is the loop's one notion of "same finding again" and is used by
`recordIgnored` (ReviewLoop.scala:143) independently of the gate. Move it
(e.g. into `IgnoredIssue.scala` or a small `private[review]` helper) and
delete `GateLedger` (class, `Owner` enum, ledger threading in
`ReviewLoopState`, `gateRejectsOf`, `gatedOut`, and `conclude`'s gate-base
folding).

## File-by-file changes

Code (`flow/src/main/scala/orca/review/` unless noted):

| File | Change |
|---|---|
| `Confidence.scala` | Delete (type, codec, `InvalidConfidence`). |
| `ConfidenceGate.scala` | Delete. |
| `GateLedger.scala` | Delete; relocate `mergeLatestByTitle`. |
| `ReviewIssue.scala` | Drop `confidence` field + its `@description`. |
| `ReviewLoop.scala` | Remove `confidenceGate` from `reviewAndFixLoop` and `ReviewLoopConfig`; delete `applyGate`/`GatedIssues` (contributions carry `List[KeyedIssue]` directly); delete gate ledger threading, `gateRejectsOf`, `gatedOut`; simplify `conclude`; drop `indexSeverities`' gate-only path; update `cleanExitMessage` doc. |
| `ReviewLoopPrompts.scala` | `initialReview` loses the `gate` param and the bar placeholders; `declinedBlock` unchanged here (PR 3 extends it). |
| `ReviewFormatting.scala` | `formatReviewerOutcome` loses `droppedCount` and the "(N below the confidence gate)" suffix. |
| `FixRequest.scala` | Exhaustive destructure in `renderIssue` loses the `confidence` slot (compile forces this). |
| `Lint.scala` | Update the stale gate comment (~line 94). |
| `flow/src/main/resources/orca/review/prompts/initial-review.md` | Replace `## Confidence` with the report-only-actionable section. |
| `flow/src/main/resources/orca/review/prompts/re-review.md` | Drop the trailing confidence sentence. |
| `flow/src/main/resources/orca/review/prompts/summarise-lint.md` | Drop the confidence sentences; instruct: report only issues worth fixing. |
| `runner/src/main/scala/orca/exports.scala` | Remove `Confidence`, `ConfidenceGate`, `InvalidConfidence` exports (lines ~53–66); fix the comment block. |
| `flows/review.sc` | Remove `ConfidentAt` (line ~76), confidence ordering/rendering in `renderSeverity`/`renderIssue` (~235–263); drop the confidence ask from `reviewPrompt` (~204). Order within a severity by reviewer/report order. |

Tests:

- `flow/src/test/scala/orca/review/ReviewLoopFixture.scala` — drop the
  `confidence` param from the issue builder (line ~159; touches most review
  tests mechanically).
- `flow/src/test/scala/orca/review/ReviewAndFixTest.scala` — delete the ~12
  gate/ledger tests; add: (a) every reported finding reaches the fixer;
  (b) a reviewer that reported findings is selected again next round
  (inverts the deleted gated-narrowing test at ReviewAndFixTest:606).
- `flow/src/test/scala/orca/review/ReviewTypesTest.scala`,
  `JsonSchemaGenTest.scala` — drop confidence decode/schema tests; keep
  severity/nullable/additional-props pins.
- `flow/src/test/scala/orca/review/ReviewLoopPromptsTest.scala` — replace
  "renders the caller's bars" (line 30) and the prompt↔schema-`@description`
  pin (line 80) with a pin of the new report-only-actionable wording.
- `flow/src/test/scala/orca/review/FixLoopTest.scala` — drop the two
  `formatReviewerOutcome` gate-suffix tests (lines 251, 258).

Docs:

- `README.md` — the `reviewAndFixLoop` row (~line 645), the quick-start
  example (~117), the type list (~803–812: delete `Confidence`/
  `ConfidenceGate` bullets, adjust `ReviewIssue`), any "confidence" text in
  the Review utilities section.
- New ADR `adr/0022-…` — the one combined ADR covering the whole rework per
  the specification: gate removal (this PR), single-pass task review +
  whole-run final review (PR 1, including the deliberate exception to
  ADR 0011's "it is the stage, not the branch, that bounds the change set"
  argument, adr/0011:217), anti-deference prompt rules (PR 3), picker recall
  (PR 4). Later PRs amend it only on deviation. No existing ADR specifies
  the gate; 0022 is free.

## Test plan

Unit only, no new fixtures; `FixOutcomeReconcileTest` unchanged (reconcile is
confidence-free already); `sbt flow/test shell/test` green with zero
warnings; scalafmt.

## Acceptance criteria

- `Confidence`/`ConfidenceGate`/`GateLedger` gone from source, exports,
  README; `grep -ri confidence flow/src runner/src flows/` returns nothing.
- A reported finding is never silently dropped: it appears in
  `FixOutcome.fixed` or, with a reason, in the returned `IgnoredIssues`.
- A reviewer that reported findings is selected again next round under the
  default selector.
- The loop terminates on: clean round, zero-fixed halt, or cap.

**Size: M** (small conceptual change, wide mechanical blast radius: ~14
source files, ~7 test files, README, 1 ADR).

---

# PR 1 — Review shape: single-pass per task, capped whole-run final loop

## Public API

**New helper — `reviewThenFix`** (in `ReviewLoop.scala`, exported from
`runner/src/main/scala/orca/exports.scala`):

```scala
def reviewThenFix[B <: BackendTag](
    coderSession: FlowSession[B],
    reviewers: List[Agent[?]],
    task: Task,
    userRequest: Option[String] = None,
    formatCommands: Configured[List[String]] = Configured.FromSettings,
    lint: Configured[Lint] = Configured.FromSettings
)(using FlowContext, InStage, FlowControl, WorkspaceWrite): IgnoredIssues
```

Deliberately narrower than `reviewAndFixLoop`: no `maxIterations` (no loop),
no `reviewerSelection`/`fixInstructions`/`diff` — the flows pass none of
them, selection is `agentDriven` (narrowing is a cross-round concept), and
the diff is the stage sample. Consumers are version-pinned scripts, so a
parameter is added when a caller needs it, not before.

Semantics: format workspace → run selection once → fan out the selected
reviewers + lint once (same `CheckedPar` fan-out, same Steps) → if findings,
one fix turn through the coder session → reconcile → announce → return. The
fixer's `fixed` claims are trusted unverified — per-task verification is
delegated to the final whole-run loop, which sees the same code with
fresh eyes instead of a convergence-primed session. `ignored` +
`unaccounted` come back in `IgnoredIssues`.

Implementation shape: refactor `ReviewFixLoop` so one round (evaluate =
format+select+fan-out; fixTurn = fix+reconcile+announce) is a named
primitive, with `run()` looping it and a new `runOnce()` calling it once —
this also discharges the dual-skeleton drift noted in
`docs/research/2026-08-review/review-loop/06-dual-loop-skeleton-drift.md`
rather than adding a third skeleton.

## Whole-run diff source

The base is the run's **starting commit, recorded in the progress header at
setup**. On a fresh run (both modes), `FlowLifecycle` captures
`git.headCommit()` at the branch-binding point — before orca's own
stack-settings/header commits — and writes it into the `ProgressHeader` as a
new `startingCommit` field; a resumed run reads it back from the header, so
the base is stable across sessions regardless of how the starting branch has
moved since. This needs no remote, works identically with `--skip-branch`,
and replaces any merge-base computation.

The header is untrusted input (hand-editable, committed): on load, validate
`startingCommit` as a hex string (arg-injection safe) — it is only ever used
as a read-only diff base, so no stronger check is needed. A header lacking
the field (hand-edited; old logs are a non-concern, consumers are
version-pinned) skips the final review with a warning Step.

Existing pieces (verified): `GitTool.reviewChanges(since)` gives diff-text +
file list of *working tree vs any rev*, untracked included, re-sampled per
call — exactly what the loop's `ReviewDiffSource.Sampled` consumes. No
`GitTool` changes are needed. Missing piece: the recorded commit reaches
neither `FlowContext` nor the loop — `FlowSetup` (constructed in
`FlowLifecycle.setup`, in scope at `DefaultFlowContext` construction,
`runner/src/main/scala/orca/flow.scala:448`) carries it there. Additions:

```scala
// ProgressHeader: startingCommit: String (fresh runs write it; validated on load)

// FlowContext: private[orca] def startingCommit: String
//   (consumed only by the loop's WholeRun resolution — not public script API)

// ReviewDiff (flow/src/main/scala/orca/review/ReviewDiffSource.scala)
enum ReviewDiff:
  case SampleFromStage
  case Pinned(diff: String)
  case WholeRun
```

`WholeRun` resolves at loop entry (the existing capabilities-at-entry
pattern; the selector's CC `->` arrow is untouched) to a diff source
sampling `git.reviewChanges(Some(ctx.startingCommit))`, its own `diffIntro`,
the base exposed for the reviewers' `baseNote`, and
`selectorFiles = git.changedFiles(Some(ctx.startingCommit))`. Bounding via
`BoundedDiff.reviewPayload` comes free through the `Sampled` path. Whether
this is a new `ReviewDiffSource.WholeRun` or a parameterised `Sampled` is
implementer's choice.

The final review itself is just
`reviewAndFixLoop(..., diff = ReviewDiff.WholeRun, maxIterations = 5)`
inside its own `stage("Final review")` — no new loop machinery, no new
public constant (the flows write the literal `5`; the flow-pin test
hardcodes it, see below).

## Per-flow adoption (`flows/`)

| Flow | Change |
|---|---|
| `implement.sc` | Per task: `reviewThenFix(...)`. After the task loop: `stage("Final review"): reviewAndFixLoop(coderSession = session, reviewers = allReviewers(reviewAgent), task = Task(Title(<final-review title>), plan.brief), diff = ReviewDiff.WholeRun, maxIterations = 5)`. |
| `implement-interactive.sc` | Same as `implement.sc`. |
| `implement-enhanced.sc` | Same; final review placed **after** "Update documentation" and before `openPrFromBranch`, so the PR carries the final fixes and the reviewers see the docs too. |
| `issue-pr.sc` | Per task `reviewThenFix(..., userRequest = Some(issuePayload))`; final review stage (same `userRequest`) before `openPrFromBranch`. |
| `issue-pr-bugfix.sc` | Per fix-task `reviewThenFix(..., userRequest = Some(issuePayload))` inside `planAndImplementFix`; final review stage after `planAndImplementFix`, before "Push fix + finalise PR". Fine that the branch carries the failing test — by final-review time the fix tasks are done. |
| `simple.sc` | No change (its one stage already spans the whole branch; the loop plays both roles). |
| `review.sc` | No change — no fixer, no loop; already a single pass. |

All five adopting flows change in this PR — adoption is coupled, not
optional: a flow with single-pass `reviewThenFix` but no final loop would
ship fixer-trusted-unverified fixes.

`shell/src/test/scala/orca/shell/flows/BuiltInFlowsTest.scala:36` ("every
fix-loop call in a flow states the library's default cap") is reworked:
`reviewThenFix` calls carry no cap; the final-review `reviewAndFixLoop`
calls must state `maxIterations = 5` (hardcoded in the test with a comment —
no public constant; `DefaultMaxIterations` keeps its existing pin role).

## File-by-file changes

- `flow/src/main/scala/orca/review/ReviewLoop.scala` — `runOnce()` +
  round-primitive refactor, `reviewThenFix`, `WholeRun` resolution at
  `reviewAndFixLoop` entry.
- `flow/src/main/scala/orca/review/ReviewDiffSource.scala` —
  `ReviewDiff.WholeRun` + its source.
- Progress header: `ProgressHeader` (`flow/src/main/scala/orca/progress/`)
  gains `startingCommit`; `FlowLifecycle` writes it on a fresh binding
  (`git.headCommit()` at the binding point) and reads it back on resume;
  header validation covers the new field (hex check). Header
  serialization/golden tests updated.
- `flow/src/main/scala/orca/FlowContext.scala`,
  `runner/src/main/scala/orca/runner/DefaultFlowContext.scala`,
  `runner/src/main/scala/orca/flow.scala` — thread `startingCommit`
  (`private[orca]`, from `FlowSetup`);
  `flow/src/test/scala/orca/TestFlowContext.scala` gains it.
- `runner/src/main/scala/orca/exports.scala` — export `reviewThenFix`.
- Five `flows/*.sc` per the table.
- Tests: `reviewThenFix` scenarios (findings→one fix turn→no re-evaluation;
  clean review→no fix turn; declines and unaccounted land in the result;
  selection runs once); `WholeRun` tests (base = recorded commit,
  re-sampling across rounds picks up fix edits, resume reads the header's
  commit, missing/invalid `startingCommit` skips with a warning);
  `BuiltInFlowsTest` rework.
- Docs: README quick-start + Review utilities table (`reviewThenFix` row,
  `WholeRun`, final-review pattern). ADR 0022 already records this PR
  (amend only on deviation).

## Test plan

Unit tests as above (in-memory `FakeAgent`/`TestFlowControl` per existing
fixture; `orca.testkit.GitRepo` for git-level tests). One live sanity run of
`flows/implement.sc` on the seeded calculator example
(`examples/runnable/01-simple`) before merge — the flow-shape change is
exactly the kind of thing unit tests under-cover; `issue-pr-bugfix.sc` has
the one non-trivial final-review placement if a second spot-check is wanted.

## Acceptance criteria

- `reviewThenFix` performs exactly one evaluation and at most one fix turn;
  every reported finding is fixed or returned in `IgnoredIssues` — never
  silently dropped.
- The final loop reviews the starting-commit-to-working-tree change set,
  re-sampled per round, capped at 5; a header without a valid
  `startingCommit` skips the final review with a warning.
- Each shipped flow compiles (scala-cli) and follows the table;
  `BuiltInFlowsTest` pins the caps.
- ADR 0022 still accurate; zero warnings; scalafmt.

**Size: L** (loop refactor + context/git plumbing + five flows + tests).

---

# PR 3 — Anti-deference reviewer prompts

## Behavior

- **Location: shared prompt templates, not the 8 per-reviewer prompts.** The
  rules are cross-cutting; ADR 0011's format keeps reviewer bodies ~25 lines
  and negative-scope focused. The rules go in `initial-review.md` (sent to
  every reviewer) and are reinforced in `re-review.md`;
  `prompts/reviewers/*.md` stay untouched.
- Rewrite/extend the existing "The plan is not evidence" section of
  `initial-review.md` (lines 35–43):
  - A deliberate or planned decision is evidence of *intent*, not of
    *correctness*; "the plan says so" and "this looks deliberate" are never
    reasons to withhold or soften a finding.
  - Mandatory-report categories: a consequence of user data loss, silent
    inversion of what the user asked for, or a blocked/hung process must be
    reported even when the plan explicitly chose the behavior, and must
    carry the severity its consequence deserves.
  - "One-line fix" describes cost, not severity: never downgrade a finding
    because the remedy is small.
- `re-review.md`: one reinforcing line, plus: where an earlier finding's
  suggestion offered alternatives, identify from the code which option was
  taken and verify that choice resolves the concern — needs no fixer data,
  preserving the "fixed titles are not sent to reviewers" design.
- `declinedBlock` (`ReviewLoopPrompts.scala:153–159`): add that a decline
  whose only reason is "the plan chose this" is not a sufficient answer for
  the mandatory-report categories — re-report it.
- **Either/or recording is prompt-only.** Extend `fix.md`: when a suggestion
  offers alternatives, state which option you took after the echoed
  key/title (key-prefix matching — `startsWithKey`, FixOutcome.scala:93–95 —
  tolerates the trailing note). No schema change; add one when a structured
  consumer of the chosen option appears.

## File-by-file changes

- `flow/src/main/resources/orca/review/prompts/initial-review.md` —
  rewritten anti-deference section.
- `flow/src/main/resources/orca/review/prompts/re-review.md` — reinforcement
  + chosen-option verification.
- `flow/src/main/resources/orca/review/prompts/fix.md` — either/or option
  statement.
- `flow/src/main/scala/orca/review/ReviewLoopPrompts.scala` —
  `declinedBlock` addition.
- Tests: `ReviewLoopPromptsTest.scala` — pin the mandatory-report wording
  and the cost-vs-severity line in both templates; pin the strengthened
  `declinedBlock`.
- Docs: none — covered by ADR 0022; amend only on deviation. README
  untouched.

## Test plan

Prompt-pin tests as above. Optionally one live `simple.sc` run against a
seeded repo with a planted planned-but-wrong behavior, as a manual spot
check.

## Acceptance criteria

- Both templates state: intent ≠ correctness; the three mandatory-report
  categories; fix cost ≠ severity. Pinned by tests.
- Re-review instructs verifying the chosen alternative resolves the concern;
  fix.md instructs stating the chosen option.

**Size: S**

---

# PR 4 — Reviewer-picker inclusion checklists

## Behavior

- **The picker already reads changed files** — `select-reviewers.md` grants
  read-only file access and instructs opening the changed files before
  excluding a reviewer. The gap is direction, not access: the picker has
  nothing concrete to look FOR. The checklist supplies that (performance:
  "the diff spawns subprocesses, performs IO, or adds work inside a loop";
  security: "parses external input, builds commands or paths from data,
  touches credentials"; …).
- **No new field: checklists go into the existing `description`
  frontmatter.** `Reviewer.description`'s only consumer is the picker
  (`ReviewerPrompts.descriptionsBySlug` → `ReviewerInfo` → per-reviewer
  lines in the selection prompt), so appending "Include when: …" clauses to
  the 8 built-in descriptions delivers the same text to the same prompt with
  zero schema change; custom reviewers simply write richer descriptions.
  Split the field only if a second consumer ever wants a short description.
- **Exclusion rationale: one optional free-text field.** `SelectedReviewers`
  gains `exclusionsRationale: Option[String]`; the prompt instructs briefly
  justifying what was left out. Auditable in the debug trace, without
  per-entry schema machinery — the schema alone could not enforce
  per-reviewer completeness anyway (an omitted name violates nothing, and
  schema enforcement is backend-dependent per `SelectedReviewers.scala:38–41`);
  the checklists are what force per-reviewer consideration.
- **The checklists are the deliverable and need research, not just
  writing**: derive each from the reviewer's own prompt scope; target 2–4
  clauses per reviewer; short beats exhaustive, but every clause must be
  checkable against a diff.

## File-by-file changes

- `flow/src/main/resources/orca/review/prompts/select-reviewers.md` —
  reference the checklists; require the exclusion rationale; keep the
  existing when-unsure-include rule.
- The 8 built-in reviewer definitions (`prompts/reviewers/*.md` frontmatter)
  — researched "Include when:" clauses appended to descriptions.
- `flow/src/main/scala/orca/review/SelectedReviewers.scala` —
  `exclusionsRationale` field.
- Tests: prompt-pin for the exclusion-rationale instruction; schema
  round-trip for the extended reply; a pin that every built-in description
  contains an "Include when:" clause.
- Docs: README (reviewer authoring section, if it documents `description`).
  ADR 0022 already names picker recall (amend only on deviation).

## Acceptance criteria

- Every built-in reviewer's description carries a researched checklist; the
  selection prompt requires an exclusion rationale.
- Prompt-pin: the performance checklist names subprocess/IO/loop signals.
  One live selection spot-check on a diff that adds subprocess calls selects
  performance.

**Size: S** (small code; the bulk is checklist research and validation).
