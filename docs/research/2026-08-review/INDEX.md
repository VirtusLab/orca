# 2026-08 review — verified findings index

48 findings across four areas, each independently verified against the code
(see the `## Verification` section in each file). Verdicts: 20 CONFIRMED,
27 CONFIRMED-REVISED (solution amended in the file), 1 CONFIRMED-WONTFIX,
0 REJECTED.

## Confirmed findings (severity-ordered)

| Area | File | Title (short) | Sev | Problem | Solution |
|---|---|---|---|---|---|
| review-loop | 01 | Fixed findings stay ignored | high | A finding declined/gated then later fixed is still reported as ignored and re-sent as declined | Prune fixed titles from `accumulated` + `GateLedger.remove` at the fix-verdict site |
| git-interface | 01 | Unbounded diff family | high | Diff family materializes unbounded git output; caps exist only downstream; deleted-file race aborts the flow | Route through `QuietProc.callCapped` (new `gitCapped`), budget `withNewFileContents`, skip-line instead of `fail` |
| git-interface | 02 | PR summary diff unbounded | high | `diffVsBase` interpolated whole into the summariser prompt; multi-MB branch kills the PR stage on every re-run | REVISED: `BoundedDiff.prPayload` head-cut, no new `GitTool` surface |
| git-interface | 03 | Dead operations | high | `add`, worktree suite, `log`/`CommitInfo`, `isDirty` have zero production callers (~200 lines) | Delete them; tighten `wholeRepoExceptOrca`/`remoteHost`; corrected test-edit ranges in Verification |
| agent-isolation | 01 | Argv and cell from one match | high | Per-backend argv building and enforcement classification are parallel matches held together by convention; drift compiles green | One `SandboxWiring`-style match per backend producing flags + cell; forwarders become exports |
| cost-pipeline | 01 | Claude error frame fabricates debit | high | Usage-less claude `is_error` emits all-zero `TokensUsed` that `TurnDebit.Unobserved` exists to prevent | `usageReported` flag at decode; `Unobserved` when the wire carried no usage |
| review-loop | 02 | Exit gate folding repeated | med | Three exit arms hand-fold gate rejects with a position-sensitive ordering only a comment states | `LoopExit` enum + single `conclude` owning the fold |
| review-loop | 03 | Pinned diff API and framing | med | `initialDiff: Option[String]` hides a three-way semantic switch; pinned diffs framed with a false stage-scope claim | Public `ReviewDiff` enum + source-dependent `diffIntro`; REVISED adds exports.scala/README/scaladoc fallout |
| review-loop | 04 | Format failures invisible | med | Failing format command produces zero signal all run; shell incantation duplicated with lint | `runShell` in Lint.scala + one Step per failing command per round (choices resolved in Verification) |
| git-interface | 04 | Change-set family redundancy | med | Eight overlapping reads; the racy parts stay public beside the composites that fix the race; "ONE sample" doc overclaims | Drop `reviewDiff`/`changedFileStats`, privatize `diffStat`/`untrackedPaths`, fix sampling doc; REVISED adds missed test ports |
| git-interface | 05 | Branch emptiness probe | med | Full branch diff materialized to answer a boolean on every successful teardown | `branchHasChangesExcludingOrca` via `git diff --quiet` exit code |
| git-interface | 06 | `show` blank success | med | Unmatched pathspec returns `Right("")`; magic pathspecs (`:(exclude)`, `:(top)`) pass validation | Blank-output → `Refused`; reject leading `:` in `GitRead.path` |
| git-interface | 07 | Best-effort probe dedupe | med | The probe shape hand-rolled seven times; `origin/HEAD` resolved twice with drifted flags | Private `probe` helper + single `originHead()` |
| git-interface | 08 | `commitStaged` order coupling | med | "forceAdd before commitStaged" is a temporal invariant only prose enforces, one caller | Merge into `forceCommitOnly`; REVISED adds FlowLifecycleTest:2406 + comment at :749 |
| git-interface | 09 | `DiffMode` single case | med | Enum with one live case; `Direct` chosen by nobody; stubs restate the default | Delete `DiffMode` and the `mode` parameter |
| agent-isolation | 02 | Opencode ReadOnly ignores `task` | med | Hard cell rationale covers four tools while `task` (subagent spawner) is in today's measured roster | Add `"task" -> false` to the read-only gate; REVISED: pin in OpencodeArgsTest (not DefaultOpencodeToolTest) |
| agent-isolation | 03 | Opencode ReadOnly keeps webfetch | med | Only backend whose ReadOnly tier keeps network; contradicts ADR 0016 and collapses the tiers permissively | Split the tiers (`webfetch` off on ReadOnly); REVISED: cell arm split + comment rewrites |
| agent-isolation | 04 | `withNetworkTools` accepts write names | med | `withNetworkTools(Seq("Bash"))` hands a NetworkOnly turn an auto-approved shell; cell still claims Hard | Denylist of write-capable builtins, throwing with a next action |
| agent-isolation | 05 | Wire invariants untested beyond codex/claude | med | `ReadOnlyTurn`-on-the-wire and dispatch-invariant restriction flags pinned only on codex/claude | Add the missing per-backend pins; REVISED: helper optional, exact argv pins specified |
| agent-isolation | 06 | Turn-entry gate repeated | med | `checkNotClosed` + announce repeated at four entry points by convention; a fifth door silently gets no notice | REVISED: template method on `AgentBackend`; `runInteractive` gains trailing `events`; doors KEEP `checkNotClosed` |
| agent-isolation | 07 | Claude per-turn MCP grant threading | med | Each host-served MCP server threaded through six parallel sites; adding a third means six easy misses | `TurnMcp` list mapped for config/approvals/hints/resources; REVISED: keep or rewire the two test constants |
| cost-pipeline | 02 | Total silently partial | med | Unpriced turns' dollars absent from an unmarked `Total:` | `anyUnpriced` flag + qualified label and legend line (made concrete in Verification) |
| cost-pipeline | 03 | Session name recovered by disk join | med | Manifest writer re-parses progress logs per event to recover a name the flow had in hand; swallowed errors reclassify kind | Carry `sessionName` on `SessionCommitted`; REVISED: FlowSession field, 2 missed matches, delete `SessionKind.Interactive` |
| cost-pipeline | 04 | Two-phase cost field, PriceList passed twice | med | `TokensUsed.cost` silently unpriced without the dispatcher; `CostTracker` takes a whole `PriceList` for one date | REVISED: narrow to `CostTracker(pricingAsOf)`, drop the `cost` default, NO `CostPipeline` factory |
| cost-pipeline | 05 | Stringly-typed outcome and kind | med | One persisted schema, four in-memory spellings, raw-string matching downstream | `ManifestOutcome`/`ManifestSessionKind` enums with `Unknown(raw)`; REVISED: `wireName` accessor + missed consumers |
| cost-pipeline | 06 | String timestamps, three parse policies | med | Timestamps cross the schema as `String`; three sites re-parse with different failure policies | Type them `Instant` (jsoniter codec round-trips); REVISED: Tables.scala stays String-rendered + golden-test guard |
| review-loop | 05 | TooLarge-then-AlreadySeen claims inline diff | low | After a paths-only round, the AlreadySeen message points at a diff the conversation never held | `LastSent` ADT (Inline/PathsOnly) selects the message; REVISED with concrete plumbing |
| review-loop | 06 | Dual loop skeleton drift | low | The two loop skeletons' halt arms drifted in user-visible messaging | Shared `fixerHaltAdditions` helper (full unification correctly rejected) |
| review-loop | 07 | Declines rationale repeated | low | Same rationale stated five times; one copy factually wrong ("last round") | One canonical statement + cross-references; fixes the error |
| review-loop | 08 | `SessionEntry` dead field | low | `entry` never read; the tag-pairing it documents has no consumer | Delete the field; optionally collapse the tag plumbing (CC-verified) |
| review-loop | 09 | Test fixture duplication | low | ~200 lines of duplicated stub/helper scaffolding across review tests | Consolidate in `ReviewLoopFixture`; REVISED: 9th selector can't use the factory |
| review-loop | 10 | Plan labels and history in comments | low | Comments carry plan labels ("BB8", "(12.7)") and change history | Exact present-tense rewrites |
| git-interface | 10 | Branch-name flag crash | low | `branchExists` puts the name in flag position; `-x` crashes with usage spam instead of the typed `Left` | REVISED: one-line `--` fix; do NOT widen the error types (would misroute in `freshRun`) |
| git-interface | 11 | Unborn HEAD setup | low | Fresh `git init` dies with an opaque rev-parse error at setup | REVISED: `headCommit().isEmpty` guard with named next action; `--show-current` option removed (breaks detached HEAD) |
| git-interface | 12 | API polish | low | `ensureClean`'s Boolean provably ignored; `diff()` naming trap re-warned at three sites | Return `Unit`; rename to `uncommittedDiff()`; REVISED: full touch list incl. SystemPromptComposer:27 |
| git-interface | 13 | Minor duplication and comments | low | `Step` wrapper 12×, `commitOnly` tail duplication, four locality-violating comments | `step` helper, reuse, delete comments; after 03/05/08 |
| git-interface | 14 | Test suite conciseness | low | Hand-rolled seeding `GitRepo.seeded()` already provides; overlapping reviewDiff tests | `withSeededRepo`, drop subsumed tests; after 03/04 |
| agent-isolation | 08 | Enforcement prose duplication | low | One fact narrated in several homes; comments restating code (~70 lines) | Single-home rule per fact; codex portion folds into 01 |
| agent-isolation | 09 | Stub enforcement boilerplate | low | 16 identical `enforcementCell` stubs; dead `[B]` on two private helpers | `StubEnforcementCell` mixin; `AgentBackend[?]`; pair with 06's rename |
| agent-isolation | 10 | Deny reason misattributes | low | Autonomous deny says "not in the auto-approve set" even under `AutoApprove.All` | Mode-aware wording; REVISED: config must be threaded (it is NOT in scope today) |
| agent-isolation | 11 | Codex fresh `--full-auto` | low | Fresh path emits the deprecated flag the resume path deliberately abandoned | Probe-gated switch to `--sandbox workspace-write`; REVISED: also update the :221 rationale string |
| agent-isolation | 12 | NetworkOnly grant summary | low | What NetworkOnly grants lives in five files with no summary | REVISED: hand-written AGENTS.md list; NO `networkGrant` SPI member |
| cost-pipeline | 07 | `ManifestUsage` inclusive convention | low | Cost log re-introduces on disk the inclusive-input convention `inclusiveInput` eliminates | Persist the disjoint axes (`freshInputTokens`); CostRecord rename is sanctioned |
| cost-pipeline | 08 | Reported zero cost suppresses estimate | low | `Some(0)` with real tokens yields an authoritative $0 that suppresses the estimate | REVISED: commit to the filter (zero + tokens → estimate) + new `PricingTest` |
| cost-pipeline | 10 | SessionPicker triplicated resolution | low | Three selectors re-implement the Resume/disabled resolution | REVISED: `resolveRow` with explicit `onShowMore` — the original helper broke a reachable branch's message |
| cost-pipeline | 11 | ManifestReaderTest dead fixture JSON | low | Fixtures carry `cost`/`turns` blocks the schema never had, under a comment claiming otherwise | Delete the blocks (~26 lines) |
| cost-pipeline | 12 | Unpinned pipeline contracts | low | `CostTracker` thread-safety and `Usage.wireTotal` residue check have no pinning tests | Add the concurrency test + two `wireTotal` cases |

### Confirmed but not worth fixing

| Area | File | Title | Reason |
|---|---|---|---|
| cost-pipeline | 09 | `CostLog.read()` has no production caller | Factually right, but moving `read()` to the test tree is pure churn: same code one hop from the `append` whose tear semantics it mirrors, re-churned when a real reader lands, for ~11 lines in an already-`private[manifest]` class |

## Cross-area conflict resolutions

- **Diff cluster** (review-loop 03/05 vs git-interface 01/02/04): they COMPOSE.
  `ReviewDiffSource.Sampled` keeps calling `GitTool.reviewChanges`/`changedFiles`,
  which git-interface 04 retains; git-interface 01's heap capping sits below the
  `ReviewDiffSource` seam; git-interface 02 was revised to use `BoundedDiff`
  only (no new `GitTool` surface), so no API conflict with review-loop 03's
  `ReviewDiff` enum. One constraint recorded in the files: git-interface 01's
  new boundedness tests target `reviewChanges().diff` (04 drops `reviewDiff`).
- **git-interface internal**: 09 changes `diffVsBase`'s signature, 01 its body,
  02 wraps its result — 09 lands first or with 01. 05 supersedes 01's item 4.
  13/14 land last (03/05/08 moot several of their items).
- **agent-isolation 01/06/07**: disjoint members but shared files. Order:
  02+03 (opencode gate, before 01 restructures that file) → 01 (+11's flag
  change and 08's codex probe consolidation folded in) → 07 (claude grant list;
  after 01's `permissionArgs` reshape) → 10 (adds an argument in the five
  `runAutonomous` bodies) → 06 + 09 (rename + stub mixin, one pass over
  doubles) → 05 → 08 (rest) → 12.
- **cost-pipeline 03/04**: overlap on `OrcaEvent.scala`/`TurnAccounting.scala`
  and shared test files, no semantic dependency — sequential in either order,
  never parallel worktrees. 03 decides `SessionKind.Interactive` (delete), so
  03 precedes 05; 05+06 rewrite the same five files (together or strictly
  sequential); 06 also touches CostLog.scala (07), ManifestReaderTest (11),
  SessionPicker (10) — sequence.
- **review-loop internal**: 05+08 both rewrite `SessionEntry` (one PR); 02
  before 06 (bail-out display lives in 06's helper, called from 02's
  `conclude`); 01 before 07 (comment subsumption).

## Suggested PR grouping

Each PR is independently mergeable; arrows are ordering constraints within an
area. No PR crosses areas.

**review-loop**
1. RL-1: 01 + 07 — fixed-findings pruning + rationale dedup (high correctness; 01's comment rewrite subsumes part of 07).
2. RL-2: 02 → 06 — `conclude` exit fold, then the shared halt helper.
3. RL-3: 03 — public `ReviewDiff` ADT + prompt framing (API change; exports + README).
4. RL-4: 04 — `runShell` + format-failure Steps.
5. RL-5: 05 + 08 — `SessionEntry` retype + dead-field deletion (same struct).
6. RL-6: 09 + 10 — test fixtures + comment hygiene (test/prose only).

**git-interface** (chain: GI-1 → GI-2 → GI-5/GI-8)
1. GI-1: 03 + 04 + 09 — trait surface reduction (dead ops, family redundancy, `DiffMode`).
2. GI-2: 01 + 05 — bounding (capped subprocess reads; emptiness probe replaces the branch-diff materialization).
3. GI-3: 02 — `BoundedDiff.prPayload` in the PR path (flow module; independent).
4. GI-4: 06 + 10 — `show` blank/pathspec refusals + `branchExists --` (small correctness).
5. GI-5: 07 — probe dedupe (after GI-1; same file).
6. GI-6: 08 — `forceCommitOnly` merge.
7. GI-7: 11 — unborn-HEAD setup guard (runner only).
8. GI-8: 12 + 13 + 14 — polish, comments, test conciseness (last).

**agent-isolation** (chain: AI-1 → AI-2 → AI-4 → AI-5 → AI-6)
1. AI-1: 02 + 03 — opencode tier gates (one change; before the 01 restructure).
2. AI-2: 01 + 11 + codex part of 08 — `SandboxWiring` per backend, deprecated-flag switch folded in, probe prose consolidated onto the wiring arms.
3. AI-3: 04 — `withNetworkTools` denylist (independent).
4. AI-4: 10 — deny-reason threading (touches the five `runAutonomous` bodies; before the 06 rename).
5. AI-5: 07 — claude `TurnMcp` grant list (after AI-2).
6. AI-6: 06 + 09 — template method + stub mixin (one pass over all doubles).
7. AI-7: 05 — wire-level pins (test-only; after AI-1).
8. AI-8: remaining 08 prose + 12 — AGENTS.md/scaladoc trims and the hand-written grant list (last).

**cost-pipeline** (chain: CP-2 → CP-3 → CP-6)
1. CP-1: 01 — claude `Unobserved` debit (claude module only).
2. CP-2: 03 — `sessionName` on `SessionCommitted` (decides `Interactive` deletion).
3. CP-3: 05 + 06 + 07 + 11 — manifest/cost-log typing (enums, `Instant`, disjoint usage axes, fixture cleanup; same file set).
4. CP-4: 02 + 04 + 12 — `CostTracker` narrowing, unpriced-total qualifier, and the pinning tests (same files, sequenced within the PR).
5. CP-5: 08 — `Pricing.resolve` zero filter + `PricingTest`.
6. CP-6: 10 — `SessionPicker.resolveRow` (after CP-3 rewrites the file).

## Rejected findings

None. All 48 findings' core factual claims held up under independent
verification (line-drift ≤ a few lines in a handful of citations, noted in the
files). One finding is CONFIRMED-WONTFIX (cost-pipeline 09, table above).
