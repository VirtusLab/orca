# Flow run cost & turn hygiene — development plan

Elaborates [00-research-plan.md](00-research-plan.md) into epics and tasks, and
tracks their state. Reviewed for correctness and completeness on 2026-08-01;
findings folded in. Status updated 2026-08-02.

## Status board

**All merged as of 2026-08-05. No PR is open.** Epics 0–6 shipped across
#45–#77; the nine findings files sit in this directory.

Merged since the 2026-08-02 board: #63 untracked symlink-to-directory · #64
manifest cost schema fix · #65 T2.5 research · #66 `FlowAuthoring` `stripMargin`
· #67 warm lint summariser · #68 T6.2 research · #69 T1.4 research (NO-GO) · #70
untracked nested repo · #71 loop ceiling and retry attribution (manifest v4) ·
#72 resumed reviewers receive the change set · #73 T2.4 research · #74 stdin
contract and strict fake · #75 change set from git · #76 comment language pass ·
#77 `session`/`apiCalls` on `ManifestTurn` (manifest v5).

Earlier: #46 · #47 · #48 · #49 · #50 · #51 · #52 · #54 · #55 · #57 · #58 · #59 ·
#61 · #62. **#56 was closed, not merged** — the skill mandate is legitimate for
this repository (T6.1).

**What remains** is under "Left to implement from the research" and "Epic 7".
All of it is either a recommendation a merged findings file made and nobody cut
a task for, or work the re-measurement created.

**Settled, no longer open questions:** cache TTL is not orca's to choose and
every observed write was 1h; resuming a reviewer within a stage is cheaper than
a fresh session; agents inheriting the operator's `~/.claude` is accepted
deliberately (agents get the operator's skills and hooks; the cost is that
run-to-run price varies with that config); no portable process-group kill exists
— see "Interrupting agents" below.

**Baseline: $44.64** — the run of 2026-08-01, `outcome: failed`, 21 sessions,
~340 turns, 36.5M prompt tokens (107k re-sent per turn; implementer 213k avg,
249k max). A prior *killed* run cost **$43.60** doing the same work. Split:
cache writes 46%, cache reads 40%, output 14%, uncached input ~0%. Prompt
caching was already on and had already saved ~77%; the levers are **prefix size
and turn count**.

Measurement sources: `.orca/cache/runs/1785572076039-880497.json` (flow, pid,
timings, outcome, 21 sessions — **no cost or token data**) and the Claude Code
transcripts at `~/.claude/projects/<project-slug>/<wireId>.jsonl` (all token
and cost figures). That orca keeps none of this itself is why Epic 0 exists.

Tasks marked **[investigate]** produce a finding file in this directory, not a
diff, and each ends in a one-line recommendation later tasks can cite.
**[blocked]** tasks must not be started until their gate clears.

---

## Epic 0 — Make cost measurable in-product (do first)

Every number above came from forensics on data orca does not keep. Without this
epic no change in the rest of the plan can be shown to have worked.
`RunManifest` records sessions, timings and outcome and no tokens or cost;
`CostTracker.summary` is printed once at exit and discarded; the only durable
per-event record is a DEBUG line rendering `Usage.toString` into a temp file
(`runner/src/main/scala/orca/runner/LoggingListener.scala:36-43`).

### **[MERGED — PR #61]** T0.1 — Cost and tokens in the run manifest
`RunManifestWriter` is already a listener receiving `TokensUsed`, and
`CostTracker` already computes `perAgent`/`perModel`/`perRole`. Add a manifest
schema version carrying per-run total `Usage` (with Epic 6's write/read split),
per-role, per-agent and per-stage subtotals, and total turn count.
*Done when:* re-running the baseline reproduces writes/reads/output/uncached
from the manifest alone, without opening a transcript. Depends on T6.1.

### **[MERGED — PR #61; the API-call count landed later in PR #77]** T0.2 — Per-turn prompt size
`ManifestTurn` records timestamp, agent, role, stage and `promptTokens`. The
api-call flag was dropped on review evidence: every backend defaults usage to
zero when a terminal frame omits it, and claude reads cost from a separate
field, so a zero-token turn can carry a billed cost and the flag could
contradict itself inside one record. `promptTokens == 0` now means "no request
observed", not "no request".
Original intent, for reference: record each turn's prompt tokens and whether it made an API call. This is what
makes "107k re-sent per turn" and "13 zero-API-call turns" reproducible rather
than one-off, and it is the only way T4.2, T8.1 and T8.2 can be evaluated.

### **[TODO — specced in PR #61, deliberately not built]** T0.3 — A checked-in benchmark flow
A fixed flow against a fixed seeded project, so before/after runs measure the
same work. Run-to-run noise otherwise exceeds every saving here except Epic 1's.
`examples/runnable/01-simple/create-test-project.sh` already seeds a
deterministic project — build on it.

---

## Epic 1 — Turn-boundary hygiene

**Problem.** Orca spawns a fresh `claude` process per turn and closes stdin
(`ClaudeBackend.scala:210-215`), so work the agent left running dies at the turn
boundary and `--resume` replays the leftovers next turn. In the baseline run
**7/7** `[Request interrupted by user]` events landed 21–63 ms after an
orphaned-background-task notification (13/13 across both runs); those turns made
zero API calls; every backgrounded `sbt` build was killed, so the agent never
read a build result, reported "fixed" unverified, and the review loop ran 11
rounds. **~$8–12/run, and the run stops failing on unverified work.**

### **[MERGED — PR #47]** T1.1 — Tell agents that background work does not survive a turn
Add a rule to `tools/src/main/scala/orca/backend/SystemPromptComposer.scala`
alongside `RuntimeOwnsGit` (`:24-28`, gated at `:34-36`): the harness process is
torn down at the end of each turn, so any backgrounded command or monitor is
killed and its result never seen; long commands run in the foreground; a result
may only be reported once observed. Orca imposes no per-turn timeout — say so,
since agents background long commands defensively.
Gate on `config.tools == ToolSet.Full` **only**. Do *not* copy `RuntimeOwnsGit`'s
`!selfManagedGit` condition: that flag means "the agent drives git", not
anything about process lifetime, and copying it would withhold the warning from
agents that can background just as easily. Consider whether `ToolSet.ReadOnly`
turns also need it — reviewers do run shell on some backends
(`ReviewerSelector.scala:82-88`).
*Done when:* the rule is asserted per-`ToolSet` in
`tools/src/test/scala/orca/backend/SystemPromptComposerTest.scala` — four
existing assertions there pin the exact composed string or `None` and will need
updating. The PR should note this tests composition, not agent behaviour.

### **[DONE — audit clean, no repo change]** T1.2 — Record where the harmful advice came from (audit, not a sweep)
A grep for background-related guidance over `tools/src/main/resources/orca/**`
and `flow/src/main/resources/orca/**` returns **zero hits** — the repo's prompt
resources are clean. The "run builds in the background" instruction came from
the run's own task brief and a user-level memory file outside the repo. T1.2
reduces to recording that in T1.1's PR body; there is no repo change.

### **[TODO]** T1.3 — **[investigate]** `is_error` arriving before orca's prompt
On `--resume` the CLI answers leftover state instantly with `No response
requested.` and emits `result` with `is_error: true` and an empty body, ~70 ms
before orca's prompt reaches stdin. Orca settles on that first result. PR #45
made it self-identifying but did not change the settle protocol. Should a
`result` preceding orca's own prompt be ignored, and how would the runtime know?
*Output:* `02-pre-prompt-results.md`.

### **[NO-GO — PR #69]** T1.4 — One CLI process per durable session
Holding stdin open across turns would let background work survive and remove the
`--resume` replay at its source. Touches `ForkedConversation`/`ClaudeBackend`
lifecycle, cancellation, teardown, and a turn that never ends. Depends on T1.3.
*Output:* an ADR draft.

### **[ANSWERED — see below]** T1.5 — **[investigate]** What forces 2.0M tokens of cache creation?
Folded in from the old Epic 7. If nothing expired (all inter-turn gaps < TTL),
the writes come from somewhere else — most likely this epic's process-per-turn
teardown re-establishing cache breakpoints under `--resume`. Confirm or refute.
*Output:* `03-cache-creation.md`. This is the largest single line item in the
run (46%), so it is the highest-value investigation in the plan.

---

## Epic 2 — Ground reviewers in a real diff

**Problem.** All ten reviewers in the baseline run received the literal
`"(no diff captured — review the working tree)"`
(`flow/src/main/scala/orca/review/ReviewLoopPrompts.scala:58-61`) and none
received a diff; each then rediscovered the change set with `git log`/`git show`
over its whole session. `GitTool.reviewDiff()` is `git diff HEAD` + untracked
(`GitTool.scala:499`), which is empty once work is committed.

### **[ANSWERED]** T2.0 — **[investigate, gates the rest of this epic]** Why was the diff empty?
The premise "the implementer commits as it goes" is **unverified and the repo
argues against it**: `SystemPromptComposer.RuntimeOwnsGit` exists to stop agents
committing, and its scaladoc names this exact failure. The baseline window shows
only three commits, all `recordAndCommit` stage commits — no agent commit. A
competing explanation is in the same reflog: a commit from the *killed* run
landed on the branch at 07:48, before the 08:14 start. The two causes have
different fixes — (a) the no-commit rule isn't reaching that turn, so fix
delivery, not the base; (b) prior-run commits, so fix resumability. Choosing a
base before settling this is guesswork.
*Output:* `04-empty-review-diff.md`, naming the cause and the fix it implies.

### **[MERGED — PR #59]** T2.1 — Base the review diff on the stage's base commit
If T2.0 confirms a base-relative diff is the fix, the base must be a **commit
SHA, not a branch name**. `<branch>...HEAD` is empty by construction in
skip-branch mode (`FlowLifecycle.scala:781` binds the feature branch to
`startBranch` verbatim) and on detached HEAD (`startBranch` reads back the
literal `"HEAD"`). Record the tip SHA of the starting branch at
`FlowLifecycle.setup` before the feature branch is created, and carry that.
Scope it to the **stage**, not the run: `reviewAndFixLoop` is called inside
`stage("Task: …")` in all four flows and `recordAndCommit` commits at every
stage exit, so a run-wide base hands task N's reviewers tasks 1..N-1's
already-reviewed code and invites re-reported findings.
Note `diffVsBase` carries **no pathspec** (`GitTool.scala:530-534`), so it does
not exclude `.orca/` — and a base-relative range always contains the
`orca: progress log` commit. `diffBranchExcludingOrca` (`GitTool.scala:295/625`)
already combines a base range with the exclusion but uses two-dot semantics;
extend it rather than adding a third near-identical method.
*Done when:* a flow that commits mid-run produces a non-empty review diff
(committed + uncommitted + untracked, `.orca/*` excluded), and in a two-task
flow task 2's diff names no file whose only change came from task 1. Falls back
to today's `diff HEAD` when no base exists.

### **[FOLDED INTO T2.1]** T2.2 — **[blocked on T2.0]** Deliver the diff without rebuilding the loop
`reviewAndFixLoop` **already has an `initialDiff: Option[String]` parameter**
(`ReviewLoop.scala:251-260`) documented for exactly this case. Evaluate the
caller-side fix (flow scripts pass it; zero library change) before threading
anything through `FlowContext` — which would mean a new accessor on the public
`FlowContext` trait, a `DefaultFlowContext` constructor param, the construction
site in `runner/src/main/scala/orca/flow.scala:411-443`, and both test doubles
(`TestFlowContext.scala:38`, `StubFlowContext` in `FlowLifecycleTest.scala`).
There is no `ReviewConfig`; the type is `private[review] ReviewLoopConfig`.
*Done when:* the fallback string appears only when the diff is truly empty, and
`flow/src/main/resources/orca/review/prompts/initial-review.md:7` — which today
says "Diff (working tree vs HEAD at the start of the review loop)" — describes
the new scope. Check `re-review.md` for the same assumption.

### **[MERGED — PR #48]** T2.3 — An empty diff must not silently drop reviewers
Independent of T2.0, and a correctness bug rather than a cost one:
`changedFiles` is `extractChangedFiles(sampleDiff())` (`ReviewLoop.scala:362`),
`Nil` on an empty diff, and `agentDriven` then filters candidates by
`filePatterns` (`ReviewerSelector.scala:126-129`). `scala-fp` is the only
reviewer with a file pattern, so on an empty diff **the Scala reviewer is
dropped before the picker sees it**, and the safety floor
(`ReviewerSelector.scala:167-176`) cannot restore it because it falls back to
`eligible`, not `all`.
*Done when:* an empty diff plus a Scala change leaves `scala-fp` eligible, with
a Step explaining the skipped filter, tested in `ReviewerSelectorTest`.

### **[RESEARCH MERGED — PR #73; the cap and the base SHA are still TODO]** T2.4
Nothing caps the diff today (`reviewDiff` concatenates unbounded,
`ReviewLoopPrompts.initialReview` interpolates unbounded). A base-relative diff
can be much larger than `diff HEAD`, so decide: stay uncapped, or reuse the
existing spill pattern — `Lint.InlineLintThreshold` (`Lint.scala:126`) inlines
≤8KB and writes the rest to `.orca/cache/` for the agent to read.
*Done when:* either the decision is recorded as "uncapped, deliberately", or a
diff over N chars renders as N chars plus a trailer naming **every** omitted
path with its line counts, with a test asserting trailer ∪ rendered = the full
change set.

### **[RESEARCH MERGED — PR #65; recommendations (b) and (c) still TODO]** T2.5 — Re-review payload
`re-review.md` carries no diff, no issue list, and no fix outcome — the fixer's
`FixOutcome` (`ReviewLoop.scala:588-590`) is never shown to the reviewer, so
each reviewer re-derives what changed every round. Measure whether ~200 tokens
of `FixOutcome` (fixed titles, ignored titles with reasons) removes tool-call
turns. Depends on T2.1/T2.2. *Output:* `05-re-review-payload.md`.

---

## Epic 3 — Reviewer fan-out and loop bounds

New epic from the completeness review; larger than Epics 4–6 combined.

### **[MERGED — PR #48]** T3.1 — Narrow the active reviewer set across rounds
`ReviewerSelector.agentDriven.prepare` (`ReviewerSelector.scala:100-177`)
computes its pick once at loop start and returns `_ => active`, discarding the
`List[ReviewBatch]` history it is handed — so every picked reviewer runs in
every round (11 rounds in the baseline).
`ReviewerSelector.onlyPreviouslyReporting` (`:46`) already implements narrowing
and **no shipped flow uses it** (`flows/implement.sc:36`,
`implement-enhanced.sc:46`, `issue-pr.sc:90`, `issue-pr-bugfix.sc:247` all pass
the default). Compose them: round 1 = the picked set; rounds ≥2 = reviewers that
reported last round, plus any whose `filePattern` matches a file touched since.
*Done when:* in a 3-round loop, a reviewer that reports nothing in round 1
creates exactly one session, asserted in `ReviewAndFixTest` — and a reviewer
dropped in round 2 still contributes its retained `gateRejects` to the final
`IgnoredIssues` (`ReviewLoop.scala:516-521`).

### **[MERGED — PR #71]** T3.2 — Attribute retries (bound dropped: no contention found)
`CheckedPar.mapParUnordered(tasks.size)(tasks)` (`ReviewLoop.scala:488`) runs
every active reviewer plus lint concurrently with no bound, while
`flows/review.sc:97` uses a bound of 4 for the same work. Combined with
`maxRetries(3)` and a predicate retrying everything but `AgentTurnFailed`
(`AgentCall.scala:279-283`), one round can be 8 agents × 4 attempts, each
re-sending a full prefix.
*Done when:* the fan-out is bounded, and retry attempts are attributed
separately in the token record (needs T0.2).

### **[MERGED — PR #71, ceiling 3]** T3.3 — Lower the default iteration ceiling
`maxIterations = 10` (`ReviewLoop.scala:249`) allows 11 evaluation rounds per
stage; only `flows/simple.sc:46` overrides it. Drop the shipped default to 3–4
and set it explicitly per flow. **Sequence after Epic 1 and Epic 2** — rounds in
the baseline were mostly reacting to unverified fixes and diffless reviews, so
cutting the ceiling first would mask those defects rather than fix them.

### **[DROPPED — owner: don't optimise an edge case; the real question is why a no-change round runs at all]** T3.4 — Skip lint when nothing changed
`runReviewersAndLint` builds the lint task unconditionally per round
(`ReviewLoop.scala:464-472`) and `lint()` re-executes the full command list every
time (`Lint.scala:55-62`) — for this repo, an sbt invocation on each round.
*Done when:* a round whose working tree is unchanged since the previous lint
reuses the prior `ReviewResult`, tested.

---

## Epic 4 — Session warmth policy (provisional)

Rebuilding N tokens of context costs the same as ~20 cache reads of it, so
resuming an agent that re-examines the same material within a stage is cheaper;
carrying one *across* stages is not. **Recorded as provisional, not settled:**
the supporting observation (follow-up rounds 4.2 turns vs 9.3 in round 1) was
measured in a run where reviewers had no diff and were rediscovering the change
set every round. Re-measure after Epic 2.

### **[DONE — PR #67]** T4.1 — Warm the lint session within a stage
**Corrected scope and value.** `Lint` is built once at loop entry
(`ReviewLoop.scala:276-280`) and stored in `ReviewLoopConfig` — leave that
alone; it is resolved outside the fan-out deliberately so capture-checked
closures never read `ctx`. The fresh session comes from the public `lint()`
helper, whose `agent…autonomous.run` mints `SessionId.fresh` on every call
(`Lint.scala:64-68` → `AgentCall.scala:34`). Mint a `Chat` on first use, keep it
in `ReviewLoopState`, resume it on later rounds. `lint` is public API
(`runner/src/main/scala/orca/exports.scala:59`) — add an overload, don't change
the signature in place.
**Value: ~$0.15/run**, not the $1.10 first claimed: the baseline ran **8** lint
sessions (not ten), all single-turn on haiku, **$0.22 total**. Do this for the
cold-start hygiene, not the money — and do it after Epic 2, since both rewrite
`ReviewLoop` internals.

### **[MERGED — PR #48]** T4.2 — Record the rule where it will be read
Put the rule, the 20-cache-reads break-even, and its provisional status in
`reviewAndFixLoop`'s scaladoc (`ReviewLoop.scala:190-217`), cross-referenced
from `ReviewerSelector`'s.

### **[DONE — PR #67: rule applied, nothing else warranted warming]** T4.3
The picker, `cheapOneShot` commit messages, and `summarisePr` each pay the
~32.4k preamble per stage. `.orca/cache/runs/*.json` records
`ManifestSession.kind = "oneShot"` — enumerate from the baseline's 21 sessions
and apply the rule to each.

---

## Epic 5 — Cost accounting

**Problem.** `ModelPricing` (`Pricing.scala:15-19`) has `input`/`cachedInput`/
`output` and **no cache-write rate**, and adapters fold cache-create into
cache-read. On any run where the CLI's `total_cost_usd` is missing —
`CostTracker.costFor` (`:76-83`) prefers it and only estimates when absent —
orca would understate the baseline by **$19.28 (45%)** and hide that writes are
the largest line item. Whether the baseline itself took the estimate path is not
recoverable from the transcripts.

### **[MERGED — PR #49]** T5.1 — Split cache-create from cache-read in `Usage`
**Three adapters, not one:** claude folds create+read
(`claude/.../streamjson/InboundMessage.scala:79-80`) and **pi** does the same
(`pi/.../rpc/InboundEvent.scala:200`); **opencode** has `cache.write` on the
wire and currently folds it into `inputTokens`
(`OpencodeConversation.scala:168-171`). codex and gemini genuinely have no write
counter and stay read-only.
`Usage` and `ModelPricing` are both public exports and are constructed
positionally in-tree — **new fields go last, with defaults**, or every
positional call site silently misbinds. `Usage` is not persisted to progress
logs or manifests, so there is no on-disk migration. Keep `Usage.+` associative
across mixed-backend runs, and preserve the documented invariant that
`cachedInputTokens` is a sub-portion of `inputTokens`.
*Done when:* a claude `result` with both counters round-trips into distinct
fields; same for pi and opencode; codex/gemini unchanged.

### **[MERGED — PR #49]** T5.2 — Add a cache-write rate to `ModelPricing`
Writes bill above base input (1.25× at 5-minute TTL, 2× at 1-hour). Decide
whether one rate or a 5m/1h pair earns its complexity given orca cannot choose
the TTL; update the shipped price list and its `checkedOn` date.
*Done when:* (i) a `result` carrying `total_cost_usd` plus both counters leaves
the reported total unchanged but shows writes and reads separately; (ii) the
same message without `total_cost_usd` estimates within 1% of a hand-computed
figure at both write rates; (iii) the PR states how many baseline turns actually
lacked `total_cost_usd`.

### **[MERGED — PR #49]** T5.3 — Surface the split in the cost summary
`CostTracker.summary` (`:129`) and `formatUsage` (`:193-204`) render one
undifferentiated "cached" figure. Writes being 46% of a run is the most
actionable fact in the summary.

---

## Epic 6 — Trim the fixed per-session preamble

~32.4k tokens ride in every session before any work: CLI system prompt, tool
schemas, `CLAUDE.md`, a `SessionStart` hook injecting a skill into *every*
session (including single-turn lint runs), and the 18k-char `direct-style-scala`
skill `CLAUDE.md` mandates for every subagent. Across ~340 turns that is 11.0M
tokens — **30% of prompt traffic, ~$5.50**; ~$1.70 per 10k trimmed.

### **[CLOSED — not a defect; PR #56 closed]** T6.1 — Scope the Scala-skill mandate
`CLAUDE.md`'s mandate is already scoped to "any Scala code **in this
repository**" — work on orca itself, which is where it belongs. Orca never
injects the skill into flows run against other repositories; those pick it up
only from their own configuration. The ~18k characters it adds to a session are
the cost of the convention being followed, not a misapplication.

What *was* misapplied: dispatch briefs telling every subagent to load the skill
regardless of role, including reviewers and summarisers that write no Scala.
That is a briefing habit, not a repo-policy problem, and it is where the
per-session preamble cost actually came from.

### **[MERGED — PR #68, re-measured via #77; premise corrected]** T6.2 — Preamble measured
Record each session's first-turn prompt size per agent kind so any trim is
verifiable. *Done when:* the figure for a single-turn lint session matches a
hand-count of system prompt + tool schemas + `CLAUDE.md` + injected skill within
5%. Depends on T0.2 and T5.1.

### Explicitly not worth doing
Trimming the eight reviewer system prompts (~20KB total,
`Reviewers.scala:139-147`): each prompt *is* that reviewer's identity, and they
are sent once per session, not per turn — roughly 1/340th of the traffic this
epic is chasing.

---

## Known but not actionable — cache TTL

Every write in the baseline was `ephemeral_1h` (2×); the 5m tier is 1.25×. Only
6 of 319 inter-turn gaps exceeded five minutes (max 8.6 min — an sbt build sits
*inside* a turn), so 5m TTL would have been cheaper: simulated **$35.95 vs
$42.36**. The CLI chooses the TTL and orca never builds a request body. The real
question — what forces 2.0M tokens of cache creation when nothing expired — is
T1.5.

## Out of scope, larger than everything here

The killed run cost $43.60 and the next redid the same work for $44.64. PR #44
refuses a duplicate run; the other half is making a killed run **resumable**,
since manifests already record per-stage session ids and `lastActiveAt`.

---

## Sequencing

**Ready now, and file-disjoint** (safe in parallel):
- **T1.1** — `SystemPromptComposer` + its test.
- **T5.1–T5.3** — `Usage`/`Pricing`/`CostTracker` + the claude, pi, opencode adapters.
- **T2.3 + T3.1** — `ReviewerSelector` (+ small `ReviewLoop` touch); one owner.
- **Commit-message bounding** (below) — `Flow.scala`.

**Bounded diff for commit messages.** `Flow.defaultCommitMessage`
(`flow/src/main/scala/orca/Flow.scala:151-168`) inlines `fc.git.diff()`
uncapped into a cheap-model prompt for a one-line message, and the baseline's
reflog shows it returning multi-line prose. Send `--stat` plus a bounded head,
and skip the call entirely when the caller supplied `commitMessage`.

**Gated:** T2.0 gates T2.1/T2.2/T2.5. T1.3 gates T1.4. T0.1 depends on T5.1.
T3.3 waits for Epics 1 and 2. T4.1 waits for Epic 2 (same file).

**Order:** Epic 0 + Epic 5 (make it measurable) → Epic 1 (largest; it changes
turn counts, so anything measured before it goes stale) → Epic 2 → Epic 3 →
Epics 4 and 6.

**File ownership for parallel work.** Epic 2 and Epic 4's T4.1 both rewrite
`ReviewLoop.scala` internals (`ReviewLoopConfig`, `ReviewLoopState`, the
fan-out) — strictly sequential, not parallel. `ReviewLoop.scala` and
`Lint.scala` compile under capture + separation checking with load-bearing
workarounds; new state threaded through `runReviewersAndLint` must stay out of
the exclusive-capability set, and `Configured` resolution must not move into the
fan-out.

## Regressions worth a test

- **Epic 1** — a long foreground command must not be killed; the rule must not
  appear on turns where it is false.
- **Epic 2** — the no-base fallback must reproduce today's `diff HEAD`
  behaviour; `.orca/*` exclusion and untracked-as-new-file rendering must
  survive; T2.3's pattern-filter collapse.
- **Epic 3** — narrowing must not lose retained `gateRejects`; a lowered
  ceiling must still fold open issues into `IgnoredIssues` with the cap reason.
- **Epic 4** — a warm lint session must not re-report a prior round's findings
  from memory when the current round's commands are silent.
- **Epic 5** — no double-counting for single-number backends; `Usage.+` stays
  associative.

---

## Findings that settled open questions (2026-08-02)

**T2.0 — why the review diff was empty.** Not orca's `recordAndCommit`, as first
assumed: every shipped flow calls `session.run(...)` and `reviewAndFixLoop`
inside the *same* stage, and `recordAndCommit` runs at stage *exit*, after the
loop. The trigger is **the coding agent committing its own work**, which nothing
in orca's prompts forbids. `reviewDiff()` is `git diff HEAD` plus untracked
contents, so once the agent commits, selection runs on `changedFiles = Nil` and
every reviewer is prompted with "(no diff captured — review the working tree)".
T2.1 is the fix and is in progress.

**T1.5 — the cache-creation question, partly answered.** Every cache write
observed across two runs was `ephemeral_1h` (3,157,329 tokens; zero
`ephemeral_5m`), so nothing expired and TTL is not the cause. What remains is
the per-turn process teardown plus `--resume` re-establishing the cache on a
grown prefix. Confirming that mechanism precisely is still open, but the TTL
hypothesis is dead.

**Interrupting agents — no portable process-group kill exists.** Measured: every
agent CLI already `setsid`s its own tool calls into a new session (claude's bash
tool call has `SESS == its own PID`; codex calls `libc::setsid()` in `pre_exec`;
gemini, opencode and pi pass node's `detached: true`). So a process group orca
establishes at spawn is not an ancestor group of anything the agent backgrounds,
and `setsid`-at-spawn — the obvious next step — is useless. Parent-side FFI
cannot retrofit a group either: `setpgid` on an already-exec'd child returns
EACCES, which is POSIX-mandated. All four CLIs `killpg` their own tool-call group
only on timeout/abort, so a `nohup … &` that returns immediately makes the tool
call *succeed* and escapes cleanly. PR #52 is therefore the right stopping point
for parent-link teardown, and covers non-detached descendants only.

## Work identified since the plan was written

### **[MERGED — PR #58]** Model aliases resolve to the wrong model
`claude:haiku` makes orca pass `--model haiku`. Measured cause: **plan mode**
(`--permission-mode plan`, used for every ReadOnly/NetworkOnly turn) has a
capability floor that silently upgrades a bare alias below it to **Sonnet 5**
while honouring a fully-qualified id — 3× the intended price, on the configuration
`ReviewerSelector` itself recommends for reviewers. Fully-qualified ids resolve
correctly. Fix: pin fully-qualified model ids at spawn. Also retires the
`claude-haiku-4-5-20251001` pricing row added in #49, whose only purpose is to
price that mis-resolved traffic.

### **[MERGED — PR #62]** Environment-cookie sweep for detached work
Inject `ORCA_TURN_ID=<uuid>` at spawn; at teardown, scan process environments
and kill matches. Environment survives `fork`/`exec` regardless of `setsid`,
double-fork or reparenting to init, which is why it reaches what parent-link
teardown cannot — measured through a real claude tool call, and reaped. ~40
lines, 24 ms over 227 processes, no dependency, no privileges; Jenkins ships the
same technique. Ship as **detect-and-report first**, kill second. Open: the
macOS path (`/proc` is Linux-only; `ps -E` or `sysctl KERN_PROCARGS2` are the
candidates, unverified).

### **[DROPPED]** Set the CLIs' own bash timeouts
Agents already time out the work they spawn — all five CLIs `killpg` their own
tool-call group on timeout or abort. Orca setting `BASH_DEFAULT_TIMEOUT_MS` and
friends would only patch two gaps (pi has no default; gemini's 300s is
*inactivity*-only) on someone else's turf, and would not touch the leak at all:
a `nohup … &` returns immediately, so the tool call succeeds and no timeout ever
fires. Recorded, not doing.
Worth knowing anyway: claude's permission matcher **strips `nohup`** before
matching, so a `Bash(nohup *)` deny rule silently does nothing (it does not
strip `setsid`).

### **[MERGED — PR #55]** Bump os-lib past 0.11.4
`os.proc(...).destroy()`/`destroyForcibly()` are root-only in 0.11.4; 0.11.5
made `destroy` walk `children()`. PR #52 is unaffected (it uses `ProcessHandle`
directly), but the `QuietProc`/`os.call` paths — lint commands, git — still get
root-only kills.

### **[MERGED — PR #54]** Issue #53 — `stripMargin` in `Lint.scala` (four sites, not one)
`$combined` is interpolated into a `stripMargin` block, so captured command
output containing `|` is mangled before the summariser reads it. Same defect
#46 fixed in `CommitDiff`.

### **[MERGED — PR #57]** `.orca-mcp-<port>.json` at the repo root
Written to the workDir root and deleted on clean exit, but a hard kill leaves it
where the next stage commit's `git add -A` sweeps it into the user's repo. It is
orca's own artifact, so the fix belongs in orca — as `.orca/cache/` already
self-ignores.

### **[TODO — unowned]** Model attribution may cross sessions
Orca logs a dated haiku id on turns billing at the Sonnet card while a session-id
join says that id belongs to fully-qualified-haiku sessions. Both can only hold
if a turn's model id and its tokens come from different sessions — i.e.
attribution crossing sessions in the concurrent reviewer fan-out. Needs an
instrumented run recording `result.model` per turn.

### **[MERGED — PR #63]** Untracked symlink-to-directory aborts the review loop
Reproduced. The predicate came in with #46 and is already on master, so
reachability is unchanged by #59 — but a review loop that aborts on a symlinked
directory in the working tree is a real defect. Two adjacent items from the same
review, also #46-owned and also deferred rather than smuggled into #59: mixed
path-relativity, and `pendingChanges`' "ONE pass" wording overstating what it
does.

### **[DONE — PR #66]** `FlowAuthoring` stripMargin (5 sites, incl. slug prompts)
Same defect class as #53, but hand-typed multi-line prose rather than machine
output, and `changePrompt` re-interpolates an already-assembled opening — so it
needs restructuring rather than a concatenation swap.

### **[DONE — PR #70]** An untracked nested git repository aborts the review loop
`git status -uall` does not recurse into a nested repo either, reporting
`?? nested/`, and `git diff --no-index -- /dev/null nested/` fails with
`error: Could not access 'nested/null'`. Reproduced. Same failure shape as the
symlink case, different cause and message, so it wants its own filter and its
own message rather than widening #63's.

### **[TODO — process]** Semantic merge conflicts are not caught by anything
Master broke when #61 (manifest cost schema) merged after #49 (renamed
`Usage.cachedInputTokens` → `cacheReadInputTokens`): different files, so no
textual conflict, but the combination does not compile. The ordering
constraints recorded in this plan are semantic and GitHub cannot enforce them.
Before merging any branch that predates a merged rename, build it against
current master rather than trusting the mergeability badge.

### **[DONE — PR #72]** Resumed reviewers receive no diff at all
`runReviewersAndLint` samples the diff only when an active reviewer lacks a
session, and the round-N active set is always a subset of round 1's — so from
round 2 the diff reaches nobody. The resumed reviewer falls back to its own
`git diff HEAD`, empty once the fixer commits, and reports clean. Same failure
#59 removed from round 1, still live in later rounds. Found by T2.5's research.

### **[MERGED — PR #74]** Latent write-after-`closeStdin`
Plus three stale claims: two saying claude needs stdin EOF (one in the
`PipedCliProcess` trait contract) and a pi-durability note contradicting
`PiBackend`. The test fake is deliberately lenient about post-close writes, so
nothing would catch a regression.

### **[DONE — PR #75]** `extractChangedFiles` missed binaries, renames and spaced paths
Replaced diff-text parsing with `git diff --name-only -z --no-relative`.
`--no-relative` turned out to be load-bearing: `changedFiles` is the only diff
call whose output goes through `asWorkDirRelative`, so `diff.relative=true`
mangles rather than truncates.

### **[SUPERSEDED — see Epic 7]** `withReadOnly` does not narrow `autoApprove`
Read-only reviewers made 199 Bash calls with zero errors under
`permissionMode: plan`; one wrote to `/tmp` and ran `scala-cli`. Falsifies
AGENTS.md's enforcement table and `EnforcementTableTest`. Found by #73. The
owner's decision is to remove the feature rather than repair it — Epic 7.

### **[TODO — new]** Eighth `stripMargin` site: `ReviewerSelectionRequest`
`${r.instructions}` is interpolated into a margin block. `instructions` is a
public overridable parameter defaulting to a loaded markdown resource, so it is
arbitrary flow-author text — the same argument on which its two siblings were
fixed. Latent: the shipped resource has no `|`-leading lines.

### **[TODO — new]** `GitRepo.empty()` leaks ambient git config into tests
Fixed for `core.excludesFile`/`core.hooksPath` in PR #70; noted here because
the class of problem (ambient config reaching fixtures) is suite-wide.

## Left to implement from the research (added 2026-08-05)

Each item below is a recommendation a merged findings file makes, for which no
task was ever cut. That is the systematic gap in this initiative: a research
task was marked done when its document landed, which closed the topic —
including the recommendation the document had just created.

### **[TODO]** Send the fixer's `ignored` titles to the re-review prompt
#65 §(b). Typically 0–3 entries, ~50–150 tokens, and the one thing in the loop
that cannot be recovered from the tree at any price: no amount of reading tells
a reviewer that the fixer judged a finding a deliberate trade-off. Today the
reviewer re-reports it, the fixer re-declines it, and the round is spent — and
the loop keeps iterating while `fixed` is non-empty, so that exchange can repeat
for the life of the loop. Frame it as the fixer's position, not a verdict, so
disagreement stays possible. Do **not** also send `fixed` titles: #65 §(a)
rejects that, because a reviewer told "this was fixed" is softened on exactly
the confidence contract it exists to apply.
*Done when:* a resumed reviewer's prompt names the declined findings with their
reasons, and a test pins that `fixed` titles are not sent.

### **[TODO]** Send the base SHA alongside the diff
#73's "coordinates *in addition to* the diff: yes, and cheap". Tens of
characters, and it lets a shell-capable reviewer go deeper. Coordinates
*instead of* the diff was measured and rejected: +32% turns, +27% tool calls,
3× the git calls per round.

### **[TODO]** Cap the initial diff, generalising machinery that already exists
#73's safety valve. Median change set 6 KB, p99 1.3 MB, max 2.1 MB (~526k
tokens); nothing caps it, so this is a hard-failure risk rather than a saving.
Three bounded-payload policies are already in the tree —
`Lint.InlineLintThreshold` (`Lint.scala:126`; ≤8 KB inline, the rest spilled to
`.orca/cache/` for the agent to read), `orca.CommitDiff`, and
`ReReviewChanges.InlineThreshold` (16 KiB, from #72). Generalise one; do not add
a fourth.
*Done when:* a diff over the threshold renders as the head plus a trailer naming
**every** omitted path with its line counts, with a test asserting
trailer ∪ rendered = the full change set.

### **[TODO — after the cap]** Per-reviewer diff increment
#65 §(c), deferred by #72, which sends the whole re-sampled change set rather
than the increment since that reviewer's own last round. #73 is explicit that
this must not ship before the diff-size policy is settled, so it follows the cap
above. Mechanism sketch from #65, unverified: a per-round tree written through a
throwaway index (`GIT_INDEX_FILE=<tmp> git add -A && git write-tree`) gives a
snapshot to diff against without touching HEAD, the real index or the working
tree; `reviewDiff(Some(previousTree))` then reads as "everything that changed
since your last round". Whether ignore rules make that snapshot agree with
`untrackedPaths()` was not checked.

### **[TODO — no owner]** The preamble's cost is the call count, not its size
#77's instrumentation re-measured T6.2 inside this repository. Reviewers average
**9.4 API calls per turn** (13.8 on reviewer turns), and the preamble is **~53%**
of all prompt traffic, not the 30% topic 4 assumed. Every trim in Epic 6 targets
the preamble's *size*, and the ceiling on all of them together is 11.3% of one
session's preamble. Nothing owns the multiplier. Same root as Epic 7: those
calls are the 199 Bash calls.

Also measured, and it removes Epic 6's largest projected saving: **no reviewer
loaded the `direct-style-scala` skill in either run**, including the run inside
this repository with the mandate in the reviewers' context (a probe quoted it
back verbatim). Both levers in T6.2 §6A target skill loading, so both would have
saved nothing.

---

## Epic 7 — Drop the read-only pretence (owner's decision, 2026-08-05)

`withReadOnly` does not narrow `autoApprove`. Read-only reviewers made 199 Bash
calls with zero errors under `permissionMode: plan`; one wrote to `/tmp` and ran
`scala-cli`. The repository therefore documents *and tests* a guarantee it does
not provide. Found by #73.

**The decision was: remove the feature rather than repair it** — it has been
bug-prone, and a guarantee that does not hold is worse than none, because it
invites designs that lean on it.

**Measurement since has inverted the premise (2026-08-05).** The failure is not
the feature; it is the mechanism two backends use. Backends that **remove the
capability** enforce it; backends that **set an approval mode** do not:

| backend | mechanism | measured |
|---|---|---|
| codex | `--sandbox read-only` (system `bwrap`, Landlock fallback) | **blocked** — policy refusal plus kernel `EROFS`; `strace`-confirmed |
| opencode | write/edit/bash/patch off | **blocked** — live integration test |
| pi | `--tools read,grep,find,ls` | unverified; flag-level only |
| claude | `--permission-mode plan` | **not blocked** — init tool list byte-identical to default mode |
| gemini | `--approval-mode plan` | unverified; also silently overridden to `default` in untrusted folders |

claude is repairable by the same shape the working backends use: `--tools`
(allowlist) is a real capability removal — `ToolSearch` cannot resurrect the
dropped tools, subagents inherit it, and it survives `--resume`. `--permission
-mode`, `--disallowedTools` and claude's own sandbox all fail: the sandbox
covers Bash only, so the Write and Edit tools write straight through it.

**Open decision:** repair claude with `--tools` and keep the tier, or remove it.
Removal now costs three working mechanisms, one of them an OS sandbox.

### **[TODO]** Write the removal plan
Enumerate every `withReadOnly` / `ToolSet.ReadOnly` call site, what each
currently promises, and what is lost by dropping it. Correct AGENTS.md's
enforcement table, and delete or rewrite `EnforcementTableTest` so nothing
asserts a property that does not hold.
*Done when:* no test and no document claims read-only enforcement the runtime
cannot deliver.

### **[ANSWERED — PR #80, no]** Can an OS-level filesystem sandbox replace it?
bubblewrap and Landlock both deliver the guarantee, but both break codex, which
nests its own bubblewrap per shell command — and codex is the backend whose
enforcement already works. Adding that exemption plus per-backend write
allowlists makes an orca-owned wrapper per-backend work with a native
dependency: the same job as using each backend's own sandbox, with more to own.
macOS unanswered (`sandbox-exec` deprecated; App Sandbox needs signing orca
cannot do). *Output:* `10-filesystem-sandbox.md`.

### **[TODO]** Repair claude with a `--tools` allowlist
Replace `--permission-mode plan` at `ClaudeArgs.scala:123` with a read-only
`--tools` allowlist: `Read,Grep,Glob,Skill` for `ReadOnly`. Makes
`Enforcement.Hard` true for claude instead of aspirational.

**`NetworkOnly` cannot take the same swap.** It gives planners GitHub reads
through command-scoped `--allowedTools` entries — `Bash(gh issue view:*)` and
four siblings (`ClaudeBackend.scala:302-310`). `--tools` takes bare built-in
names, so those cannot move into it, and a straight swap would silently remove
the planner's `gh` access. **Owner's decision (2026-08-05): planning must keep
network access.** Open shapes, pending the probe in `12-reviewer-tool-surface.md`:
keep `Bash` in the allowlist and scope with `--allowedTools` (tier drops to
`PromptOnly`, matching codex and pi), or expose the `gh` reads as MCP tools
(tier stays a real no-edit tier, since MCP passes through `--tools` unfiltered).

If `--allowedTools` patterns only grant and do not confine — as #74 measured for
the plain form — then `NetworkOnly`'s `Hard` claim at `ClaudeArgs.scala:153` is
already false, and claude has two broken tiers, not one.
**Cost:** read-only agents lose Bash entirely — no git, no build, no shell `rg`.
Reviewers already get the diff inlined (#73), but any flow that relies on a
read-only agent shelling out breaks.
**Caveats:** MCP tools pass through `--tools` unfiltered; unknown tool names are
dropped silently, so a CLI rename would strip a tool without an error.
*Done when:* an integration test pins the resulting `init` tool list, and a
write attempt under `bypassPermissions` reaches no filesystem.

### **[TODO — unowned]** Measure gemini
Deferred: no credentials on this host. Same mechanism as claude's, so the
`Hard` claim at `GeminiArgs.scala:104` should not be treated as established.
Separately, gemini silently overrides `--approval-mode plan` to `default` in
untrusted folders — which is where orca runs agents.

---

## Accepted, not to be revisited

- **Agents inherit the operator's `~/.claude`.** Deliberate: agents get the
  operator's skills and hooks. The cost is that a run's behaviour and price vary
  with that config, so cross-machine comparisons are not like-for-like.
- **Cache TTL** is chosen by the CLI, not orca.
- **`setsid` at spawn, FFI `setpgid`, cgroup scopes** — ruled out above.
