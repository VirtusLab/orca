# T6.2 — Measuring the fixed per-session preamble

Epic 6 asserts that ~32.4k tokens ride in every session before any work, from a
hand-count of agent transcripts. This establishes the figure from measurement
and attributes it to components, so a trim can be justified and verified.

**Headline: the figure holds.** A single-turn reviewer session measured
**32,119 prompt tokens** (mean of five, spread 187 — 0.6%), against a claim of
~32.4k: within 1%. But only **~2.0k of it is orca's**. 83% is the backend CLI's
own system prompt and tool schemas, which orca cannot change, and ~1.6k is the
operator's plugin and hook configuration.

**The framing needs one correction.** The preamble is not per *session*, it is
per *API call*. `--resume` does not amortise it: the system prompt and tool
schemas are re-sent on every request. A turn that makes one tool call pays it
twice. So "340 turns × 32.4k = 11.0M" is a lower bound, not an estimate.

**Where the money actually is.** Not the preamble's size — its multiplication.
Each extra API iteration in an otherwise single-call session costs a full
preamble (32.1k), which is 35× what the `SessionStart` hook that Epic 6
singles out costs in the first place (925). Anything that turns a one-call
reviewer into a two-call reviewer is the expensive change.

---

## 1. What orca now records, and what it still cannot answer

Manifest schema v3 (PR #61) adds `RunManifest.turns: List[ManifestTurn]`, each
carrying `at`, `agent`, `role`, `stage` and `promptTokens`, plus per-role,
per-agent and per-stage subtotals in `ManifestCostSummary`.

`promptTokens` is `Usage.inputTokens`, which per `Usage`'s normalisation
contract is the **total** prompt, inclusive of cache reads and writes. So it is
the right preamble proxy: no reassembly from the cache axes is needed.

Two gaps stop it from answering T6.2 on its own.

**No session identifier on a turn.** A session's *first* turn is only
identifiable when the agent name happens to be unique to the session. That
holds for reviewers (one agent name per reviewer session) and fails for the
implementer: in the measured run, four sessions and six turns all carry
`agent: "main"`, and no field separates them. The writer already computes the
dedup key (`RunManifestWriterState.upsertSession`, `wireId.getOrElse(clientId)`)
— it just isn't carried onto the turn.

**No API-call count on a turn.** `TokensUsed` fires once per turn with the
usage summed over every API iteration inside it. In the measured run,
`security` recorded 32,141 and `code-functionality` 64,044 — one call and two
calls, not one turn with a 2× larger prompt. Without a call count the per-call
preamble cannot be divided out, and the single most expensive effect in this
report (an extra iteration costing a full preamble) is invisible in the data.

Both are addressed in §6.

## 2. Measurement method

Two independent measurements, which agree.

**(a) A real orca run.** No manifest in the repo's `.orca/cache/runs/`
post-dates PR #61 — all nine are v2 and carry no `turns`. So a run was
generated: `implement.sc` against a scratch git repo, task "add a line to
README.md". It produced a v3 manifest with 11 sessions and 13 turns. Its
reviewer sessions are single-turn, read-only, and did essentially no work, so
their prompt *is* the preamble with a small brief attached.

**(b) CLI ablation.** Each component was switched off and the total prompt
re-measured, using the same CLI build orca drives (2.1.220) and the same flags
`ClaudeArgs.streamJson` passes. One `--print --output-format json` call each,
`--model claude-haiku-4-5` (token counts are model-independent; this only makes
the probe cheap). Total prompt = `input_tokens + cache_creation_input_tokens +
cache_read_input_tokens`. The as-configured baseline was run three times:
28,902 / 29,016 / 28,902 — mean **28,940**, so deltas above ~150 are signal.

All ablations ran in an empty non-repo directory, so they exclude the git
environment block and any project `CLAUDE.md`; those are added back in §4.

## 3. Component attribution

| Component | Tokens | Owner |
|---|---:|---|
| CLI system prompt + built-in tool schemas + env block | ~26,725 | backend (not orca's) |
| User `~/.claude/CLAUDE.md` + its `@RTK.md` import (2,318 chars) | 567 | operator |
| `superpowers` `SessionStart` hook injection (3.4 KB) | 925 | operator |
| `superpowers` skill listings (15 skills) | 373 | operator |
| Other plugins — MCP tools + skill listings | 350 | operator |
| `--permission-mode plan` surcharge (every read-only agent) | 1,186 | backend, orca-triggered |
| Orca project `CLAUDE.md` (459 chars) | ~270 | repo |
| Orca standing rules via `--append-system-prompt-file` (1,061 chars) | ~200 | orca |
| Orca reviewer identity prompt (largest: code-structure, 4,046 chars) | ~940 | orca |
| `direct-style-scala` SKILL.md body (18,382 chars), **when loaded** | 5,025 | repo policy |

Raw ablation totals, for reproduction:

| Configuration | Total prompt |
|---|---:|
| All plugins disabled | 27,292 |
| `superpowers` disabled (other plugins on) | 27,642 |
| All hooks disabled (`disableAllHooks`, plugins on) | 28,015 |
| `--strict-mcp-config` | 28,801 |
| **As configured (baseline, mean of 3)** | **28,940** |
| \+ orca project `CLAUDE.md` in cwd | 29,210 |
| \+ orca standing rules as appended system prompt | 29,142 |
| \+ user memory chain duplicated as a cwd `CLAUDE.md` | 29,526 |
| \+ `--permission-mode plan` | 30,126 |
| \+ plan mode + code-structure reviewer system prompt | 31,268 |
| \+ `direct-style-scala` SKILL.md as a cwd `CLAUDE.md` | 33,984 |

Two results worth stating plainly:

**Plan mode costs more, not less.** Every read-only agent — every reviewer,
every lint session — pays a **+1,186** token surcharge over a full-tool agent.
Plan mode removes tool *availability*, not tool *schemas*, and adds its own
instructions. The intuition that read-only sessions are the cheap ones is
backwards.

**The `direct-style-scala` skill is 5,025 tokens** for its 18,382 characters,
confirming the "18k-char" figure in the epic. It enters context as a tool
result, not as preamble — which is what makes it expensive (§5).

## 4. Cross-check: attribution vs. the real run

Summing the components a reviewer session pays: 26,725 + 567 + 925 + 373 + 350
+ 1,186 + 940 + 200 = **31,266**. Measured in the manifest: **32,119**. The
993-token gap is orca's review brief and the git-repo environment block,
neither of which the ablation dirs contained.

T6.2's done-when is agreement within 5%. The component sum is **3.1%** below
the measured figure before the brief is counted, and closes once it is.

The implementer's preamble comes out lower: `main` turns that made a single API
call recorded 30,366 and 30,524, against a predicted ~29.4k plus brief.

| Agent kind | Fixed preamble | Source |
|---|---:|---|
| Reviewer / lint (`ToolSet.ReadOnly`, plan mode) | **~31.3k** | measured, 32,119 incl. brief |
| Implementer (`ToolSet.Full`) | **~29.4k** | measured, ~30.4k incl. brief |

## 5. Amortisation per agent kind

From the measured run. Task: append one line to a README.

| Agent | Turns | API calls | Input tokens | Output | In:out | Cost |
|---|---:|---:|---:|---:|---:|---:|
| security | 1 | 1 | 32,141 | 132 | 244:1 | $0.325 |
| test | 1 | 1 | 32,148 | 110 | 292:1 | $0.324 |
| readability | 1 | 1 | 31,998 | 112 | 286:1 | $0.323 |
| performance | 1 | 1 | 32,123 | 152 | 211:1 | $0.325 |
| simplicity | 1 | 1 | 32,185 | 114 | 282:1 | $0.174 |
| code-functionality | 1 | 2 | 64,044 | 241 | 266:1 | $0.193 |
| code-structure | 1 | 2 | 65,912 | 267 | 247:1 | $0.203 |
| **reviewer role** | **7** | **9** | **290,551** | **1,128** | **258:1** | **$1.867** |
| main (implementer) | 6 | ~19 | 569,891 | 4,074 | 140:1 | $1.152 |
| **run total** | **13** | — | **860,442** | **5,202** | **165:1** | **$3.019** |

**A single-turn reviewer does not amortise the preamble at all.** Nine reviewer
API calls × ~32.1k = ~289k of the 290,551 tokens the role consumed — **99.5%
preamble**. Four of the five one-call reviewers ran on opus with the whole
preamble as fresh 1h cache creation, at **$0.32 per session, ~100% of it
preamble**. Seven reviewers is **~$1.87 per review round** before any reviewer
reads a line of the diff.

**A multi-turn implementer amortises it slightly better, but not much**, and
not because of `--resume`: each of its ~19 API calls re-sent the preamble too.
Its better ratio comes from carrying real conversation alongside it. On a
trivial task the preamble still floors ~98% of its traffic.

**Two reviewers cost double because they made one tool call.** `Read` on a
one-line README turned a 32.1k session into a 64.0k one. This is measured, not
projected, and it is the mechanism that matters most below.

*(The cost column is not monotone in tokens — `simplicity` paid $0.174 for the
same 32.1k that cost `security` $0.325 — because one hit an existing cache and
the other created a 1h entry at 2× base input. That is T1.5's question, not
this one's.)*

## 6. Proposed trims

Ordered by measured saving. **None implemented — the owner decides.**

### A. Keep read-only agents to one API call — orca's to own

A reviewer that makes a single extra tool call pays a second full preamble:
**+32.1k, +100%**, measured. If it invokes the `direct-style-scala` skill the
second call also carries the 5,025-token skill body: **+37.1k, +115%** for a
session that writes ~130 tokens of findings.

This is the live risk in this repo. The CLI auto-loads the project `CLAUDE.md`
into every session in the working tree, reviewers included, and that file
mandates the skill for *reviewing* Scala as well as writing it. T6.1 correctly
found the mandate itself legitimate — the fix is not to remove it but to scope
it away from read-only roles, which write no code and need no style guide.

Two independent levers, either sufficient:

- **Repo `CLAUDE.md`** — narrow "writing, modifying, or reviewing" to the roles
  that produce Scala. Owner: this repo. Saving: **up to 37.1k per reviewer
  session, ~260k per 7-reviewer round.**
- **Orca** — a standing rule on `ToolSet.ReadOnly` turns in
  `SystemPromptComposer`, alongside `BackgroundWorkAbandonedAtTurnEnd`: you have
  been given what you need; do not load skills or re-read guidance. Owner: orca.
  Same saving, and it also covers flows run against repositories whose own
  `CLAUDE.md` orca does not control. Cost: ~40 tokens on every read-only turn.

The `superpowers` `using-superpowers` injection pushes the same way — "invoke
relevant skills BEFORE any response" — which is the strongest argument for
disabling that hook, far stronger than its own 925 tokens.

### B. Disable the `SessionStart` hook for non-interactive sessions — operator's

**925 tokens per session**, ~10.2k across this run's 11 sessions. The hook fires
on `startup|clear|compact` and not on `resume`, so orca pays it once per
session, not once per turn — which makes it **2.9% of a reviewer's preamble**.

Epic 6 names it as the most obvious candidate. On the numbers it is not: it is
worth a fifth of one reviewer's extra tool call. Its real cost is indirect, via
(A). Owner: `~/.claude/settings.json`.

### C. Leave the reviewer identity prompts alone — confirmed

The largest (`code-structure`) is **~940 tokens, 2.9%** of that reviewer's
preamble. The plan already calls this not worth doing; the measurement agrees.

### D. Nothing else clears the noise floor

Non-superpowers plugins 350, user memory chain 567, project `CLAUDE.md` ~270,
orca's standing rules ~200. Together **~1.4k, 4.3%** of a reviewer's preamble,
spread across three owners. Not worth coordinating.

### E. The 83% is not available

~26.7k of every session is the CLI's own system prompt and tool schemas, plus
1,186 for plan mode. Orca chooses plan mode, but not what plan mode costs. This
is the honest ceiling on Epic 6: **even a perfect trim of everything orca, the
repo and the operator control removes ~17% of the preamble.**

## 7. Proposed instrumentation — specced, not built

Both gaps in §1 are one field each on `ManifestTurn`:

- `session: String` — the dedup key the writer already computes, making
  "first turn of each session" exact rather than inferred from a unique agent
  name.
- `apiCalls: Long` — iterations inside the turn, making the per-call preamble
  divisible out and an extra tool call visible as what it costs.

Deliberately not implemented here. Either bumps `RunManifest.SupportedVersion`
to 4, and readers skip any version they do not write — so it invalidates every
v3 manifest, one commit after v3 shipped. That is the owner's call, not a side
effect of a measurement task.

## 8. What could not be measured

- **Per-API-call counts for the implementer.** `main`'s ~19 calls are inferred
  by dividing turn totals by the measured preamble, not recorded. Its 140:1
  ratio is therefore approximate; the reviewer figures are exact.
- **The hook and skill-listing split within `superpowers`** rests on
  `disableAllHooks`, which also disables the `PreToolUse` hook. That hook injects
  no context, so the 925 should be clean, but it was not isolated further.
- **A reviewer session inside this repository.** The run used a scratch repo, so
  no project `CLAUDE.md` was present and no reviewer loaded the skill. (A)'s
  37.1k is computed from separately measured parts — 32.1k preamble, 5,025 skill
  body — and from the measured doubling when a reviewer made one tool call. It
  is a projection from measurements, not a single observation.
- **Whether the ~340-turn baseline's 11.0M holds.** It assumes one API call per
  turn. Turns in this run averaged ~2.2, so the true figure is higher; by how
  much cannot be recovered without the per-turn call count in §7.
- **The money.** Preamble tokens bill at three different rates (base, 1h cache
  write at 2×, cache read at 0.1×) and this run's mix was 39% write / 61% read.
  A token saving does not convert to a dollar saving at one rate, so savings are
  quoted in tokens throughout.
