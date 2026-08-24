# Implementation state — 2026-08 review PR bundles

28 bundles from INDEX.md. One Opus implementer at a time, isolated worktree each.
Each area is a linear branch stack in index order: the first bundle branches from
`origin/master`, every later bundle branches from the previous bundle's branch in
the same area. Merge each area's PRs in stack order (GitHub retargets after each
merge).

Branch naming: `review/<bundle>` (e.g. `review/cp-1`).
Dispatch order (round-robin across areas): CP-1, RL-1, GI-1, AI-1, CP-2, RL-2,
GI-2, AI-2, CP-3, RL-3, GI-3, AI-3, CP-4, RL-4, GI-4, AI-4, CP-5, RL-5, GI-5,
AI-5, CP-6, RL-6, GI-6, AI-6, GI-7, AI-7, GI-8, AI-8.

Commit/PR rules: Adam Warski sole author, no co-author line, no generated-with
footer. PR bodies: simplest possible language, concise, reference the finding
files.

## Status

| # | Bundle | Findings | Base branch | Branch | PR | Status |
|---|--------|----------|-------------|--------|----|--------|
| 1 | CP-1 | cost-pipeline/01 | origin/master | review/cp-1 | #108 | done |
| 2 | RL-1 | review-loop/01+07 | origin/master | review/rl-1 | #109 | done |
| 3 | GI-1 | git-interface/03+04+09 | origin/master | review/gi-1 | #110 | done |
| 4 | AI-1 | agent-isolation/02+03 | origin/master | review/ai-1 | #111 | done |
| 5 | CP-2 | cost-pipeline/03 | review/cp-1 | review/cp-2 | #112 | done |
| 6 | RL-2 | review-loop/02+06 | review/rl-1 | review/rl-2 | #113 | done |
| 7 | GI-2 | git-interface/01+05 | review/gi-1 | review/gi-2 | #114 | done |
| 8 | AI-2 | agent-isolation/01+11+08(codex part) | review/ai-1 | review/ai-2 | #115 | done |
| 9 | CP-3 | cost-pipeline/05+06+07+11 | review/cp-2 | review/cp-3 | #116 | done |
| 10 | RL-3 | review-loop/03 | review/rl-2 | review/rl-3 | #117 | done |
| 11 | GI-3 | git-interface/02 | review/gi-2 | review/gi-3 | #118 | done |
| 12 | AI-3 | agent-isolation/04 | review/ai-2 | review/ai-3 | #119 | done |
| 13 | CP-4 | cost-pipeline/02+04+12 | review/cp-3 | review/cp-4 | #120 | done |
| 14 | RL-4 | review-loop/04 | review/rl-3 | review/rl-4 | #121 | done |
| 15 | GI-4 | git-interface/06+10 | review/gi-3 | review/gi-4 | #122 | done |
| 16 | AI-4 | agent-isolation/10 | review/ai-3 | review/ai-4 | #123 | done |
| 17 | CP-5 | cost-pipeline/08 | review/cp-4 | review/cp-5 | #124 | done |
| 18 | RL-5 | review-loop/05+08 | review/rl-4 | review/rl-5 | #125 | done |
| 19 | GI-5 | git-interface/07 | review/gi-4 | review/gi-5 | #126 | done |
| 20 | AI-5 | agent-isolation/07 | review/ai-4 | review/ai-5 | #127 | done |
| 21 | CP-6 | cost-pipeline/10 | review/cp-5 | review/cp-6 | #128 | done |
| 22 | RL-6 | review-loop/09+10 | review/rl-5 | review/rl-6 | #129 | done |
| 23 | GI-6 | git-interface/08 | review/gi-5 | review/gi-6 | #130 | done |
| 24 | AI-6 | agent-isolation/06+09 | review/ai-5 | review/ai-6 | #131 | done |
| 25 | GI-7 | git-interface/11 | review/gi-6 | review/gi-7 | #132 | done |
| 26 | AI-7 | agent-isolation/05 | review/ai-6 | review/ai-7 | #133 | done |
| 27 | GI-8 | git-interface/12+13+14 | review/gi-7 | review/gi-8 | #134 | done |
| 28 | AI-8 | agent-isolation/08(rest)+12 | review/ai-7 | review/ai-8 | #135 | done |

All 28 bundles implemented (PRs #108–#135, 2026-08-07/08). Merge each area's
stack in order; GitHub retargets the next PR after each merge.

## Phase 2 — review, fix, merge

One Opus agent per PR, sequential (one sbt at a time): checkout PR branch,
merge origin/master in, parallel review subagents (no sbt), fixer subagent,
tests, push fixes, squash-merge + delete branch. Order:
cp #108 #112 #116 #120 #124 #128 · rl #109 #113 #117 #121 #125 #129 ·
gi #110 #114 #118 #122 #126 #130 #132 #134 · ai #111 #115 #119 #123 #127 #131 #133 #135

Merged: #108 (fix: usage→Option[Usage] ADT, comment corrections),
#112 (fix: sticky session name vs chat(session.id) regression, upsert takes event, named args),
#116 (fix: wire spellings single-homed via wireName lookup, Unknown branches tested),
#120 (fix: shared Usage.spentTokens, single state read in summary, Ox concurrency test),
#124 (fix: filterNot polarity, no-table-row zero pinned, legend wording, named args),
#128 (fix: notResumable prefix string, by-name dropped, stale scaladoc) — CP STACK DONE,
#109 (fix: prune documented in all carried-set docs, gate test precondition),
#113 (fix: fixerHaltAdditions flattened, conclude arm names/unwrap),
#117 (fix: README pinned-diff correction, enum docs, initialReview named args),
#121 (fix: stale formatCommands scaladoc, doc trims, exitCode binding),
#125 (fix: PathsOnly comment states whole-diff fact, os.exists over AtomicBoolean),
#129 (fix: two more stubs onto StubAgent, fixture doc accuracy) — RL STACK DONE,
#110 (fix: sampling docs aligned, isDirty inlined),
#114 (fix: UTF-8 byte budget not chars, honest skip labels, marked/ReadOutput dedupe),
#118 (fix: bound moved into summarisePr — bugfix flow bypassed it; surrogate test),
#122 (fix: refusal message covers untouched-path/merge-commit blanks, dead conjunct),
#126 (fix: probe/probeSucceeds split, scaladoc accuracy; --short validated empirically),
#130 (fix: failure contract documented, staged-index scoping test),
#132 (fix: orphaned scaladoc, refusal covers non-repo + nothing-staged cases),
#134 (fix: ADR stale ref, doc accuracy; per-suite counts verified) — GI STACK DONE,
#111 (fix: restrictedToolsOff rename, rationale accuracy, ADR 0016 amendment),
#115 (fix: sandboxModeArgs dedupe, doc claims corrected, ADR codex row; wire byte-identical),
#119 (fix: Monitor added to denylist (2.1.226 probe), degenerate match, message rewrite),
#123 (fix: denialCause branches on set membership — in-set opencode ask no longer blames the set),
#127 (fix: doc dedup, named args on TurnMcp; wire identity verified; caught silent bad automerge),
#131 (fix: hooks re-protected at 37 double sites, listener-threading test added, dup test removed),
#133 (fix: wrong PromptOnly comments deleted, resume asserts added, shared harness helpers),
#135 (fix: grant list corrected — --allowedTools named; stale teardown claim confirmed dead) — AI STACK DONE

ALL 28 PRs MERGED (2026-08-08, master ae74258d, 2007 tests green).

## Phase 3 results (2026-08-08) — all four live tests PASS

Version 0.1.3+92-ae74258d-SNAPSHOT via publishLocal, isolated XDG env per
CONTRIBUTING.md. simple.sc on claude ($0.1417) and codex ($0.0107 est.):
succeeded, manifests/cost logs correct, zero fabricated usage rows.
implement.sc interrupt (SIGINT to pgid mid-stage) + resume on both backends:
failure teardown + reset --hard, resume skipped completed stages (banner
"Resuming from recorded result", no duplicate commits, durable wireId
continuity on claude), final outcome succeeded.

Follow-up candidates from live testing:
1. codex default model absent from pricing table — headline total covers a
   fraction of real spend on out-of-box codex config
2. reviewer selection picker returned no usable names 4/4 runs (claude:haiku
   and codex) — fallback to all 7 reviewers masks a real failure
3. failure teardown reset --hard leaves untracked files (README overclaims)
4. session record written after last stage commit is reverted by teardown —
   resume re-seeds instead of resuming the live conversation
5. env leakage: global skills/RTK reach flow agents (non-hermetic runs)

## Phase 4 — live-test follow-ups (user decisions)

1. FIX — codex default model unpriced. | 2. FIX + verify by live test —
reviewer picker returns no usable names. | 3. FIX — teardown leaves untracked
files. | 4. RESEARCH ONLY — answer whether the reverted session record
matters (user: last-stage commit = flow complete; conversations continuable
from manifest?). | 5. BY DESIGN — no action.
One problem at a time: research → implement → review → fix → PR → merge.

| # | Problem | Branch | PR | Status |
|---|---------|--------|----|--------|
| 1 | codex model unpriced | fix/codex-model-pricing | #136 | merged (default pins gpt-5.6-sol; priced-default test; live probe clean) |
| 2 | reviewer picker broken | fix/reviewer-picker | #137 | merged + live-verified both backends (minItems:1 schema; claude picks 3+3, codex picks 2+1, zero fallbacks) |
| 3 | teardown untracked files | fix/teardown-untracked | #138 | merged (scoped clean -e .orca no -x; caught showUntrackedFiles=no data-loss path) |
| 4 | session-record research | — | — | done: NON-ISSUE by design. Window is mid-flow, one stage wide (mint/wireId written between stage commits, reverted with the stage). Re-seed is state-consistent (teardown wiped the edits the conversation references); ADR 0018 §2.6 names re-seed the guaranteed fallback. Manifest survives teardown; orca continue can reattach to the orphaned conversation. Do NOT add manifest→progress-log recovery (lossy join, resurrects stale context). Optional cosmetic polish (eager mint commit) considered and skipped. |

Phase 4 complete: #136, #137 (live-verified both backends), #138 merged; #4 answered.

## Phase 5 — final live test + log audit follow-ups

Final live test (master 28a79228): picker verified both backends (claude 5/1/1/4/3,
codex 2/1, zero fallbacks). Log audit findings A1-A4/B/C/D recorded in audit report.
A1 investigation verdict: fixer judgment sound; root cause = reviewers see only the
task TITLE (the "plan is not evidence" prompt section is inoperable) + decline
reasons captured but never printed. Open policy question for user: unresolved
Warning at halt exit commits+exits 0.

| Item | PR | Status |
|------|----|--------|
| A2 turn-prose ordering | #139 | merged (release on next turn's activity; claude renders all turns) |
| A1-primary reviewer context | #140 | merged (Task type to reviewers, labelled user request, userRequest override for issue flows) |
| A1-reporting unfixed findings | #141 | merged (Unresolved findings block with reasons at every exit) |
| A3 By-role untagged spend | #142 | merged ((untagged) bucket rendered; sections sum to total) |

Open items: standoff exit-code policy (user decision pending); audit findings not
yet addressed: A4 rounding, B1 tool results, B2 picker names, B3 fix-turn ignores
(partly covered by #141), B4 iteration rationale, C1 coursier noise, C2 bash
truncation, C3 fan-out attribution, C4 dup collapse, D1-D7 polish, --verbose no-op;
from #139: retry.md vs StructuredOutputMode, opencode RawText label.

Noted for later (from #139): retry.md ignores StructuredOutputMode; opencode
RawText label likely wrong (ADR 0014).

## Phase 6 — remaining-task PRs (user decisions 2026-08-09)

Skipped: standoff exit policy (as-is), D5 (as-is). D3 pending user yes/no.
| PR | Items | Status |
|----|-------|--------|
| R1 | README: drop ⎿ promise + --verbose | #143 merged (+ SKILL.md; riders → R2) |
| R2b | review-loop wording | #144 merged (names+lint in fan-out line, re-review clause, D2 truthful reword, riders) |

After R7: final live-test round (claude+codex) verifying the new output:
picker names, re-review connective, attribution, bash headlines, dup collapse,
headline fields, (untagged) cost section, header models, coursier suppression,
unresolved-findings block, prose ordering. D3 still pending user yes/no.
| R2 | review-loop wording | #144 merged |
| R3 | terminal rendering | #145 merged (attribution w/ Many-state, sh -c strip, ⎿ ×N collapse, headline fields) |
| R4 | cost/model reporting | #146 merged (sum-group units; spec.model orElse configuredModel; <harness default>) |
| R5 | launcher coursier suppression | #147 merged (--quiet --verbose pair; --quiet alone was silent-failure bug; 907→0 lines) |
| R6 | structured-output prompting | #148 merged (opencode=Tool verified+fixed incl. drain-mode blocker; Tool prompts 2-line; retry-tool.md; codex stays RawText — resume can't pass schema) |
| R7 | review internals (empty-diff LastSent case, drop RosterEntry[B]) | in progress |

| R7 | review internals | #149 merged (NoteOnly LastSent case; RosterEntry[B] dropped, CC fixtures bite) |

Follow-ups noted from R6: interactive.md "no such tool exists" false for
claude/opencode (needs interactiveClosingProse mode branch); codex resume
accepts --output-schema — passing it would make codex enforced every turn.
D3 still awaiting user yes/no.

## Final live round (2026-08-24, master 9da3d07c, v0.1.3+106)

Both backends exit 0, variance implemented+committed. Verification audit:
ALL 14 exercised checklist items PASS (coursier silence, picker names,
re-review connective, attribution, bash wrapper strip, headline fields,
prose ordering, clean-exit block absence, cost units recomputed to the cent,
header models, (untagged) sums, priced codex, no fallbacks). 3 not exercised
(dup collapse, ignored-findings block, lint reviewer). Log sizes 2224→145
(claude), 195→62 (codex). Zero regressions.

Final-audit follow-up decisions (2026-08-24): PR A = findings 1-3 + 8's
scaladoc (in progress); PR B = findings 4+5; PR C = D3 (approved: notice once
at run start, un-indented, distinct glyph). 6 = by design (resume checkpoint),
no action. 7 = haiku was test-only config; orca defaults are opus/gpt-5.6-sol,
no action.

New findings from the final audit (ranked, none fixed yet):
1. tool headlines span 3 lines when input has \n (git commit -m body) —
   collapse after unescape (oneLine in ToolInputSummary.headline+CommandHeadline)
2. fixer narrates I1/I2 keys the findings list never shows — print key in formatIssue
3. HeadlineFields: path outranks pattern → "Grep (.)" zero-info headlines
4. scala-cli ANSI leaks into non-tty log (2 lines)
5. "(untagged)" label opaque — "main (no role)" would answer it
6. stage: commits carry only progress-log bookkeeping when implementer self-commits
7. model-quality: 2 of 5 claude findings false (pycache "tracked"), 2 dup-reported
8. attribution not retroactive within a stage; scaladoc overclaims

NOTE: #111's branch deletion CLOSED #115 instead of retargeting (recovered:
reopened + retargeted via REST API — `gh pr edit --base` fails on this repo).
Remaining processors: pre-retarget the next stacked PR's base to master via
`gh api -X PATCH repos/VirtusLab/orca/pulls/<n> -f base=master` BEFORE merging
with --delete-branch, then verify the next PR is still open.

Follow-up candidates (out of scope during merges): empty-diff sample as a third
LastSent mode (pre-existing wording gap); remove dead RosterEntry[B] type param
(~8 files incl. CC test fixture).

## Phase 3 — live tests (after all PRs merged)

On merged master, per CONTRIBUTING.md's local-testing recipes, run live:
1. simple flow — claude backend
2. simple flow — codex backend
3. flow interrupted mid-run, then resumed from the progress log — claude
4. same interruption+resume — codex
Verify: flow completes, manifest/cost log written, resume picks up from the
progress log without redoing completed stages.

Skipped: cost-pipeline/09 (CONFIRMED-WONTFIX).

## Final-audit PRs complete (2026-08-24)
- #151 headlines/keys/Grep + attribution scaladoc (merged after #152 unblock)
- #152 git 2.55 show detection (--name-only --format= probe; CI git upgrade)
- #153 (no role) label; ANSI leak deliberately not fixed (pipe = uninterruptible
  hang, reproduced; pre-compile alternative = user's call)
- #154 D3: OrcaEvent.Caveat, yellow ! un-indented; run-start prediction rejected
  (tiers unknown at header time)
Open (user's call): ANSI pre-compile trade-off; ShellOutput.error tty check;
interactive.md false claim + codex resume --output-schema (from R6).
