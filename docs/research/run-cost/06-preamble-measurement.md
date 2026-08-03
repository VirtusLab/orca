# T6.2 — Measuring the fixed per-session preamble

Epic 6 asserts that ~32.4k tokens ride in every session before any work, from a
hand-count of agent transcripts. This establishes the figure from measurement
and attributes it to components, so a trim can be justified and verified.

**The figure holds.** A single-turn reviewer session measured **32,119 prompt
tokens** (mean of five, spread 187 — 0.6%), against a claim of ~32.4k: within
1%. But only **~2.0k of it is orca's** — the standing rules (200), the reviewer
identity prompt (940) and the review brief inside §4's 853-token residual. 83%
is the backend CLI's own system prompt and tool schemas, which orca cannot
change, and ~1.6k is the operator's plugin and hook configuration.

**The preamble is per API call, not per session.** `--resume` does not amortise
it: the system prompt and tool schemas are re-sent on every request. A turn that
makes one tool call pays it twice. So "340 turns × 32.4k = 11.0M" is a lower
bound, not an estimate.

**The cost is how often the preamble is re-sent, not its size.** Each extra API
iteration in an otherwise single-call session costs a full preamble (32.1k), 35×
what the `SessionStart` hook Epic 6 singles out costs (925). Anything that turns
a one-call reviewer into a two-call reviewer is the expensive change.

**A second run, inside this repository, replaced the projections with
measurements** (§9). The preamble size holds again — reviewer first calls came
in at 32,109–33,022. Two other things did not. Reviewers here made **9 to 20 API
calls each**, not one or two, so "an extra tool call" understates the cost by an
order of magnitude. And **no reviewer loaded the `direct-style-scala` skill**,
in a repository whose `CLAUDE.md` mandates it and is in their context — so
§6A's 37.1k, which assumes they do, rests on something that did not happen.

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

Both are addressed in §6. **Both are now built** — PR #77 adds `session` and
`apiCalls` to `ManifestTurn` (schema v5). See §7 for what shipped and for the
counter that looked right and was not.

## 2. Measurement method

Two independent measurements, which agree.

**(a) A real orca run.** No manifest in the repo's `.orca/cache/runs/`
post-dates PR #61 — all nine are v2 and carry no `turns`. So a run was
generated: `implement.sc` against a scratch git repo, task "add a line to
README.md". It produced a v3 manifest with 11 sessions and 13 turns. Its
reviewer sessions are single-turn, read-only, and did almost no work, so their
prompt *is* the preamble with a small brief attached.

> **That manifest is not reproducible from this repository.** It was written to
> a scratch repo outside the tree and is neither committed here nor attached to
> the PR; the nine manifests under `.orca/cache/runs/` are all v2. So **every
> figure in §4 and §5 that comes from source (a) — 32,119, the per-agent table,
> the 290,551 role total — is uncheckable against anything a reader has.** They
> are reported as measured because they were, not because they can be
> re-derived. Source (b) below is fully reproducible, and is what the rest of
> the document leans on.

**(b) CLI ablation.** Each component was switched off and the total prompt
re-measured, using the same CLI build orca drives (2.1.220) and the same flags
`ClaudeArgs.streamJson` passes. Total prompt = `input_tokens +
cache_creation_input_tokens + cache_read_input_tokens`. The exact command, one
per configuration:

```
claude --print --output-format json --model claude-haiku-4-5 \
  [ablation flags] "Reply with the single word: ok"
```

`--model claude-haiku-4-5` only makes the probe cheap; the prompt is one line so
that the measured total is the preamble and nothing else. The as-configured
baseline was run three times: 28,902 / 29,016 / 28,902 — mean **28,940**, so
deltas above ~150 are signal.

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

Two results:

**Plan mode costs more, not less.** Every read-only agent — every reviewer,
every lint session — pays a **+1,186** token surcharge. Plan mode removes tool
*availability*, not tool *schemas*, and adds its own instructions. Read-only
sessions are not the cheap ones.

*The control arm above is wrong, and had to be re-measured.* The +1,186 is
plan-mode against **no permission flag at all**. That is not what a full-tool
orca agent emits: `ToolSet.Full` + `AutoApprove.All` produces
`--permission-mode bypassPermissions` (`ClaudeArgs.scala:134`), an arm the
original ablation never ran. Re-measured, three runs each on the same CLI build
in the same empty directory:

| arm | total prompt |
|---|---:|
| no permission flag | 28,900 / 28,900 / 28,900 |
| `--permission-mode plan` | 30,238 / 30,239 / 30,237 |
| `--permission-mode bypassPermissions` | 28,900 / 28,900 / 28,900 |

`bypassPermissions` is indistinguishable from no flag, so both comparisons give
the same answer, and the surcharge over a real full-tool agent holds at **+1.2k
to +1.3k**. The figure survives; the arm that establishes it was measured
afterwards, not in the original ablation. The ~29.4k implementer prediction in
§4 is likewise unaffected.

**The `direct-style-scala` skill is 5,025 tokens** for its 18,382 characters,
confirming the "18k-char" figure in the epic. It enters context as a tool
result, not as preamble — which is what makes it expensive (§5).

## 4. Cross-check: attribution vs. the real run

Summing the components a reviewer session pays: 26,725 + 567 + 925 + 373 + 350
+ 1,186 + 940 + 200 = **31,266**. Measured in the manifest: **32,119**. The
**853**-token gap is orca's review brief and the git-repo environment block,
neither of which the ablation dirs contained.

T6.2's done-when is agreement within 5%. The component sum is **2.66%** below
the measured figure before the brief is counted, and closes once it is.

*What this checks.* The sum itself is not independent evidence: 26,725 is
defined as the residual (baseline 28,940 minus the four operator components), so
adding the components back cannot disagree with the ablation — it reproduces the
ablation row "plan mode + code-structure reviewer system prompt" by
construction. The comparison that counts is across *methods*: an ablation total
built in a scratch directory (b) against a prompt size recorded by a real orca
run (a). Those agree to 2.66%. The internal sum is bookkeeping.

The implementer's preamble comes out lower: `main` turns that made a single API
call recorded 30,366 and 30,524, against a predicted ~29.4k plus brief.

| Agent kind | Fixed preamble | Source |
|---|---:|---|
| Reviewer / lint (`ToolSet.ReadOnly`, plan mode) | **~31.3k** | measured, 32,119 incl. brief |
| Implementer (`ToolSet.Full`) | **~29.4k** | measured, ~30.4k incl. brief |

*Both rows are confirmed by the second run (§9), this time inside this
repository rather than a scratch one.* Its five reviewers opened at 32,109 /
32,157 / 32,174 / 32,674 / 33,022 — mean **32,427** against 32,119 here, a 1.0%
spread. Its three `main` turns opened at 30,202 / 30,787 / 30,819, against
~30.4k here. The project `CLAUDE.md` those sessions carried and the scratch-repo
ones did not is worth ~270 (§3), which is about the size of the difference.

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

**A reviewer that makes no tool call does not amortise the preamble at all.**
Nine reviewer API calls × ~32.1k = ~289k of the 290,551 tokens the role consumed
— **99.5% preamble**. Four of the five one-call reviewers ran on opus with the
whole preamble as fresh 1h cache creation, at **$0.32 per session, ~100% of it
preamble**. Seven reviewers is **~$1.87 per review round** before any reviewer
reads a line of the diff.

*99.5% is the ceiling of a degenerate case, not a typical figure.* The task was
"add a line to README.md" in a scratch repo with a one-line README: five of
seven reviewers made zero tool calls, so their whole prompt *was* the preamble.
Real reviewers in this repository do not look like that. Over the baseline run's
ten reviewer sessions (270
deduplicated API messages, cold-start mean 32,658), the preamble is
**46.7%** of prompt tokens; the rest is the diff, tool results and the
conversation. The 99.5% is the bound this measurement can reach, not the number
to plan against.

*The second run (§9) settles that.* Its five reviewers, in this repository on a
592-line diff, spent **4,347,662** prompt tokens over **69** API calls. Charging
each call its own opening prompt gives **2,240,027** of preamble — **51.5%**,
against the 46.7% predicted above and the 99.5% of the degenerate case. The
46.7% was the right number to plan against.

**A multi-turn implementer amortises it slightly better, but not much**, and
not because of `--resume`: each of its ~19 API calls re-sent the preamble too.
Its better ratio comes from carrying real conversation alongside it. On a
trivial task the preamble is still ~98% of its traffic.

**Two reviewers cost double because they made one tool call.** `Read` on a
one-line README turned a 32.1k session into a 64.0k one. This is measured, not
projected, and it is the mechanism behind §6's largest trim.

*The mechanism is right; "one extra call" is the wrong scale.* In the second run
(§9) no reviewer stopped anywhere near one call — the five made 9, 9, 12, 19 and
20. The in:out ratio there is **63:1**, not the 258:1 of the table above, and
one round of five reviewers cost **$7.18**, against $1.87 for seven here. Per
reviewer that is $1.44 against $0.27.

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
mandates the skill for *reviewing* Scala as well as writing it. T6.1 found the
mandate itself legitimate — the fix is not to remove it but to scope it away
from read-only roles, which write no code and need no style guide.

Two independent levers, either sufficient:

- **Repo `CLAUDE.md`** — narrow "writing, modifying, or reviewing" to the roles
  that produce Scala. Owner: this repo. Saving: **up to 37.1k per reviewer
  session, up to ~260k per 7-reviewer round.** Both are ceilings, and the round
  figure is the looser of the two: it assumes all seven reviewers make an extra
  call *and* load the skill on the same round. No round like that was observed —
  in the measured run no reviewer loaded the skill at all (§8), and two of seven
  made the extra call.
- **Orca** — a standing rule on `ToolSet.ReadOnly` turns in
  `SystemPromptComposer`, alongside `BackgroundWorkAbandonedAtTurnEnd`: you have
  been given what you need; do not load skills or re-read guidance. Owner: orca.
  Same saving, and it also covers flows run against repositories whose own
  `CLAUDE.md` orca does not control. Cost: ~40 tokens on every read-only turn.

The `superpowers` `using-superpowers` injection pushes the same way — "invoke
relevant skills BEFORE any response" — which is a better reason to disable that
hook than its own 925 tokens.

**Measured, and it does not hold up.** Everything above was written from parts
measured separately. The second run (§9) ran the real thing — five reviewers, in
this repository, on a 592-line Scala diff — and it corrects two halves of (A)
in opposite directions.

*The doubling is real and is a floor, not a ceiling.* Second calls cost 44,181
(readability), 44,168 (test), 44,682 (scala-fp), 33,129 (code-structure) and
32,210 (code-functionality). So one extra call costs a full preamble plus
whatever the first response and its tool result added — 32.2k at the low end,
44.7k at the high end, against the +32.1k projected here.

*The skill half did not happen.* Across 69 reviewer API calls there were **zero
`Skill` invocations**. The mandate was in context — a probe in the same
directory and the same `--permission-mode plan` had the model quote it back
verbatim ("Before writing, modifying, or reviewing any Scala code in this
repository, invoke the `direct-style-scala` skill"). Reviewers were told to load
it and did not. So the 5,025-token skill body, and with it the 37.1k and the
~260k per round, are savings on a cost that has now been looked for twice and
found neither time.

*And "one extra call" is the wrong unit.* The five reviewers made 9, 9, 12, 19
and 20 API calls, using 47 `Bash` and 22 `Read` calls between them, all under
`ToolSet.ReadOnly` / `--permission-mode plan`. The expensive thing is not a
reviewer slipping from one call to two. It is a reviewer exploring the
repository for twenty calls, each re-paying the preamble.

*What that means for the levers.* Both levers target skill loading. On this
evidence the repo-`CLAUDE.md` lever and the orca standing-rule lever would each
have saved **nothing** in this run, because the behaviour they suppress did not
occur. The cost that did occur — 9 to 20 exploration calls per reviewer — is the
same shape as what PR #73 measured: read-only reviewers making 199 `Bash` calls
under `permissionMode: plan`. A prompt rule is the instrument that measurement
found ineffective against exactly this behaviour, so this data gives no reason
to expect a prompt rule to work here either. It does not show the levers
failing — they were never triggered — it shows they aim at the smaller number.

### B. Disable the `SessionStart` hook for non-interactive sessions — operator's

**925 tokens per session**, ~10.2k across this run's 11 sessions. The hook fires
on `startup|clear|compact` and not on `resume`, so orca pays it once per
session, not once per turn — which makes it **2.9% of a reviewer's preamble**.

Epic 6 names it as the most obvious candidate. On the numbers it is not: 925
against 32,119 is a **thirty-fifth** of what one extra reviewer tool call costs.
Its real cost is indirect, via (A). Owner: `~/.claude/settings.json`.

### C. Leave the reviewer identity prompts alone — confirmed

The largest (`code-structure`) is **~940 tokens, 2.9%** of that reviewer's
preamble. The plan already calls this not worth doing; the measurement agrees.

### D. Nothing else clears the noise floor

Non-superpowers plugins 350, user memory chain 567, project `CLAUDE.md` ~270,
orca's standing rules ~200. Together **~1.4k, 4.3%** of a reviewer's preamble,
spread across three owners. Not worth coordinating.

### E. The 83% is not available

~26.7k of every session is the CLI's own system prompt and tool schemas, plus
1,186 for plan mode. Orca chooses plan mode, but not what plan mode costs.

The ceiling on Epic 6 is therefore **11.3%**: user memory 567 + hook 925 + skill
listings 373 + other plugins 350 + project `CLAUDE.md` 270 + standing rules 200
+ reviewer identity 940 = **3,625** of 32,119. That is everything orca, this
repo and the operator can actually change, trimmed perfectly to zero.

The larger figure — 32,119 − 26,725 = 5,394, or 16.8% — is not a ceiling on
trimming. It counts the 1,186 plan-mode surcharge, called unavailable above,
plus §4's 853-token residual, most of which is the review brief the loop needs
to state the task at all. Those two are reachable only by not running a
read-only reviewer, or by not telling it what to review.

## 7. The instrumentation — specced here, built in PR #77

Both gaps in §1 are one field each on `ManifestTurn`:

- `session` — the dedup key the writer already computes, making "first turn of
  each session" exact rather than inferred from a unique agent name.
- `apiCalls` — model requests inside the turn, making the per-call preamble
  divisible out and an extra tool call visible as what it costs.

Both shipped in PR #77, at schema v5. Both are `Option`: a backend that cannot
count leaves `apiCalls` unset, because a guessed count would read downstream
exactly like a measured one — which is the whole reason this section exists.
Only claude reports a count; codex, gemini, opencode and pi do not.

**The counter that looked right and was not.** The first implementation read
`num_turns` off claude's result message. Four probes agreed with the
`message_start` stream events exactly — 1, 2, 4 calls, and 2 on a `--resume`.
The second run (§9) broke the agreement: `num_turns` exceeded the number of
distinct model responses on six of eight turns. `num_turns` is **(tool calls +
1)**. The probes agreed only because in each of them the model made one tool
call per response. A follow-up probe confirmed it: one response issuing three
tool calls at once produces 2 `message_start` events and reports `num_turns: 4`.

The shipped count is the number of **distinct `id`s on the turn's `assistant`
messages**, which matches `message_start` on every run checked. The ids have to
be deduplicated — the CLI splits one response into several `assistant` messages
that repeat the id, so counting messages over-reports as badly as `num_turns`.

Two limits worth carrying:

- A turn that dispatches a subagent counts the subagent's responses too — the
  CLI forwards them on the same stream. Measured on one such turn: 53 responses
  counted against 18 in the dispatching session's own transcript. Its token
  total is the CLI's aggregate and matched neither, so `promptTokens /
  apiCalls` means little on a turn like that.
- On every other turn checked — 10 of 11 in a validation run on the shipped
  build — the manifest's `apiCalls` equalled the transcript's distinct response
  ids exactly.

## 8. What could not be measured

- **Per-API-call counts for the implementer.** `main`'s ~19 calls are inferred
  by dividing turn totals by the measured preamble, not recorded. Its 140:1
  ratio is therefore approximate; the reviewer figures are exact.
- **The hook and skill-listing split within `superpowers`** rests on
  `disableAllHooks`, which also disables the `PreToolUse` hook. That hook injects
  no context, so the 925 should be clean, but it was not isolated further.
- ~~**A reviewer session inside this repository.**~~ **Measured — see §9.** The
  first run used a scratch repo, so no project `CLAUDE.md` was present and no
  reviewer loaded the skill. (A)'s 37.1k was a projection from separately
  measured parts — 32.1k preamble, 5,025 skill body — plus the measured doubling
  when a reviewer made one tool call. Run inside this repository, the preamble
  and the doubling both hold and the skill body does not: no reviewer loaded the
  skill there either.
- ~~**Whether the ~340-turn baseline's 11.0M holds.**~~ **Corrected — see §9.**
  It assumed one API call per turn. Turns averaged ~2.2 in the first run and
  **9.4** in the second, so the product cannot be the way to compute it: 340
  turns × 32.4k × 9.4 is 103M, roughly three times that baseline's entire prompt
  traffic. What is recoverable is the share, and it measures about half, not
  30%. The absolute 11.0M is still unrecoverable — that baseline's per-turn call
  counts were never recorded, and `apiCalls` only exists from PR #77 onward.
- **The money.** Preamble tokens bill at three different rates (base, 1h cache
  write at 2×, cache read at 0.1×) and this run's mix was 39% write / 61% read.
  A token saving does not convert to a dollar saving at one rate, so savings are
  quoted in tokens throughout.

## 9. Second run — reviewers inside this repository

The §8 gap that mattered was that everything above came from a scratch repo. So
`review.sc` was run against a clone of this repository at `b6f1d920`, reviewing
a 592-line, 16-file Scala diff (PR #77's own change). `reviewAgent` was
`claude:opus`, the operator's real setting. The flow picked five reviewers.
**Total cost $7.35**, $7.18 of it the reviewers.

| Reviewer | API calls | Prompt tokens | 1st call | Last call | Out | In:out | Cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| readability | 9 | 474,123 | 32,174 | 66,664 | 9.9k | 48:1 | $1.118 |
| test | 12 | 694,350 | 32,157 | 74,706 | 10.5k | 66:1 | $1.168 |
| scala-fp | 9 | 478,098 | 32,674 | 64,228 | 11.9k | 40:1 | $0.996 |
| code-structure | 20 | 1,236,771 | 33,022 | 80,515 | 13.1k | 94:1 | $1.561 |
| code-functionality | 19 | 1,464,320 | 32,109 | 105,702 | 23.9k | 61:1 | $2.334 |
| **five reviewers** | **69** | **4,347,662** | | | **69.3k** | **63:1** | **$7.177** |
| main (haiku, 3 turns) | 6 | 184,523 | | | 4.7k | 39:1 | $0.174 |
| **run total** | **75** | **4,532,185** | | | **74.0k** | **61:1** | **$7.351** |

Four results.

**The preamble size holds a third time.** First calls 32,109–33,022, mean
32,427, against §4's 32,119 — 1.0% apart, with a project `CLAUDE.md` present
this time.

**The preamble is about half the traffic, not 30%.** Every call carries one, so
charging each of the 75 calls its own opening prompt gives 2,423,611 of
4,532,185 — **53.5%** (52.1% if the ~853-token review brief is taken out of the
floor). Reviewers alone are 51.5%; the three haiku `main` sessions are 99.5%.
The nearest earlier figure is §5's 46.7% for the baseline run's reviewer
sessions, so the two directly measured shares agree at about half, and the
30% in `00-research-plan.md` topic 4 — computed as one call per turn — is low
by roughly a factor of two.

**Turns are not 1 or 2.2 calls.** 75 calls over 8 turns is **9.4 per turn**;
reviewer turns alone are **13.8**.

**No reviewer loaded the skill.** Zero `Skill` invocations across 69 calls, with
the mandate in context (verified: a probe in the same directory under the same
`--permission-mode plan` quoted the sentence back). Tool use was 47 `Bash` and
22 `Read`.

*Caveat on how the counts were obtained.* The run itself was made with the build
that still read `num_turns`, so the call counts above are re-derived from the
same run's CLI transcripts by the corrected rule — distinct response ids, which
is what the shipped code counts (§7). A separate, cheaper run on the shipped
build matched transcripts on 10 of 11 turns; the 11th dispatched a subagent.

*Caveat on scope.* One run, one diff, one flow, five reviewers. It says nothing
about `implement.sc`'s fix loop, and the 592-line diff is a mid-sized one — a
larger diff would push the exploration calls up and the preamble share down.

> **Like §2(a), this run is not reproducible from this repository.** Its
> manifest and CLI transcripts were written to a clone outside the tree and are
> not committed here or attached to any PR. The figures above are reported as
> measured because they were, not because a reader can re-derive them.

### The correction owed to `00-research-plan.md` topic 4

That file is not on this branch — it exists only as an uncommitted file in the
working tree — so the correction is recorded here for whoever holds it. Topic 4
currently reads "Across 340 turns that is 11.0M tokens — **30% of all prompt
traffic, ~$5.50**". What survives and what does not:

- **~32.4k per session holds.** Three independent measurements land within 1%.
- **11.0M and 30% do not.** They assume one API call per turn, and the preamble
  is paid per call. Measured turns averaged 2.2 calls in the first run and 9.4
  in the second, so the multiplication is not the way to compute it — at 9.4 it
  exceeds that baseline's whole prompt traffic.
- **The share is the recoverable quantity**, and it measures 46.7% (§5) and
  53.5% (§9) in the two runs where it was measured: about half of all prompt
  traffic, not 30%.
- **The absolute 11.0M stays unrecoverable.** That baseline's per-turn call
  counts were never recorded, and the manifest only carries `apiCalls` from PR
  #77 onward.
- **The skill half of the question is weaker than it assumes.** Topic 4 rests
  partly on "the 18k-char `direct-style-scala` skill that `CLAUDE.md` mandates
  for every subagent". In two runs — including one inside this repository, with
  the mandate in the reviewers' context — no reviewer ever loaded it.
