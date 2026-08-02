# 09 — Inlined diff vs. coordinates: how the change set reaches a reviewer

Scope: T2.4 from `01-development-plan.md` ("Decide the diff-size policy
explicitly"), widened to the question behind it — should orca keep inlining the
whole review diff into each reviewer's prompt, or hand over *coordinates* (a
base commit, a branch, a file list) and let each reviewer run whatever commands
it needs at its own discretion?

The coordinates idea was raised as an instinct, with an explicit invitation to
refute it. Method: source reading, measurement over the Claude Code transcripts
of eight recorded runs, a live CLI probe, and scratch-repo experiments; then an
adversarial critique pass that overturned parts of the first analysis. Where the
two disagree the critique is authoritative, and the correction is recorded
inline.

## Answer

**Coordinates *instead of* the diff: no.** Unimplementable on pi and opencode,
and on claude it works only because of a permission defect (§2). Where it does
work it buys a round-trip per reviewer for tokens it does not save (§3).

**Coordinates *in addition to* the diff: yes, and cheap.** Telling reviewers the
base SHA costs tens of characters and lets a shell-capable reviewer go deeper.
That is the part of the instinct that should ship.

**The real T2.4 problem is the tail** (§4). The median change set is 6 KB; the
p99 is 1.3 MB and the maximum 2.1 MB (~526k tokens). Nothing caps it. That is a
hard-failure risk, not a cost problem, and should be sized as a safety valve
rather than as a saving.

**The cap should not be a new mechanism.** `orca.CommitDiff` already implements
the bounded-payload shape this ticket asks for. Generalise it (§5).

---

## 1. What happens today

Path, at `59597ca5`:

1. `reviewAndFixLoop` reads `fc.stageBaseCommit` at loop entry
   (`flow/src/main/scala/orca/review/ReviewLoop.scala:297`) into
   `ReviewLoopConfig.reviewBase` (`:346`), so the fan-out closures carry a plain
   hash rather than the exclusive capability it came from (`:294-296`).
2. `ReviewFixLoop.sampleDiff()` (`:377-378`) =
   `initialDiff.getOrElse(ctx.git.reviewDiff(reviewBase))`.
3. `OsGitTool.reviewDiff` (`tools/src/main/scala/orca/tools/GitTool.scala:573`)
   = `git diff <base> -- :(top) :(exclude).orca/*`, concatenated with a
   `--no-index` new-file diff per untracked path (`:584-588`).
4. `runReviewersAndLint` samples once per round, and only if some active
   reviewer lacks a session (`:477-478`).
5. `firstReview` (`:435`) renders it through `ReviewLoopPrompts.initialReview`
   (`ReviewLoopPrompts.scala:58-68`) as `s"```diff\n$diff\n```"` into
   `{{diffBlock}}` (`prompts/initial-review.md:9`).

**There is no cap anywhere on this path.**

Two incidental defects found while tracing it:

- `sampleDiff()` runs **twice** in round 1 — eagerly at `:385` to build
  `changedFiles`, then again at `:478`. Two `git diff` invocations over a
  possibly-large tree, and the second can differ from the first because the
  format pass at `:562` runs between them.
- `initial-review.md:7` still says "Diff (working tree vs HEAD at the start of
  the review loop)", stale since #59 made it stage-base-relative;
  `select-reviewers.md:8` repeats it; and `reviewAndFixLoop`'s scaladoc
  (`:262-267`) describes per-iteration re-sampling no shipped selector can
  reach. *These are being fixed separately, with the resumed-reviewer defect
  (§6); recorded here because they are the same surface.*

---

## 2. Can a read-only reviewer run git?

Reviewers are built `.withReadOnly` (`review/Reviewers.scala:139-147`).

| backend | ReadOnly emits | source | shell? |
|---|---|---|---|
| claude | `--permission-mode plan` | `ClaudeArgs.scala:121` | yes — **via a defect** |
| codex | `--sandbox read-only` | `CodexArgs.scala:134` | yes |
| pi | `--tools read,grep,find,ls` | `PiArgs.scala:16,52` | **no** |
| opencode | `"bash": false` | `OpencodeArgs.scala:84-90` | **no** |
| gemini | `--approval-mode plan` | `GeminiArgs.scala:78` | untested |

### Why claude reviewers run git — and why that is not a foundation

The first analysis concluded "plan mode simply permits shell reads". That is
wrong, and the true mechanism matters.

A live probe (claude 2.1.220) shows plan mode **blocking** Bash pending
permission, even for a pure read — a bare `claude -p` has nobody to answer, so
the command never runs:

```
$ claude -p --permission-mode plan --model claude-haiku-4-5 \
    "Run exactly this with the Bash tool and show me its output: git --version"
The permission system is blocking this. You need to approve the Bash command.
Once you do, I'll run `git --version` and show you the output.
```

Orca answers that prompt automatically:

- `Agent.withReadOnly` sets only `tools`; it does not touch `autoApprove`
  (`tools/src/main/scala/orca/agents/Agent.scala:119`).
- `AgentConfig.autoApprove` defaults to `AutoApprove.All`
  (`agents/AgentConfig.scala:21`).
- `ClaudeConversation.handleControlRequest` replies `Allow()` whenever
  `autoApproves(name)`, unconditionally `true` under `All`
  (`claude/.../ClaudeConversation.scala:243-259`).

So claude reviewers get a shell because orca auto-approves the prompt plan mode
raises. Measured in the baseline run: ten reviewer sessions, every one recording
`permissionMode: plan`, issuing **199 Bash calls with zero `is_error`** —
including one which ran

```
cd /tmp && cat > dtest.scala <<'EOF' … EOF
scala-cli run --scala 3.8.4 dtest.scala
```

— a file **write** and arbitrary program execution from a nominally read-only
reviewer.

Three consequences:

1. **A coordinates design on claude would rest on a defect.** `AGENTS.md`'s
   enforcement matrix and `EnforcementTableTest` record claude `ReadOnly` as
   `Hard`; `ClaudeArgs.scala:102-104` claims plan mode "makes Edit/Write/Bash
   unavailable"; `ReviewerSelector.scala:84-85` claims "claude's plan mode
   doesn't [run commands]". The tests pin only the flag string
   (`ClaudeArgsTest.scala:97-133`), never the behaviour. If anyone makes
   `withReadOnly` narrow `autoApprove` — which the documentation says it already
   does — every coordinates-only reviewer on claude goes blind in that commit.
2. **This is a finding in its own right**, larger than T2.4 and deserving its own
   ticket: a reviewer can write files and execute programs in the flow's tree.
   Under this repo's trusted-but-fallible threat model it is a *correctness*
   problem — a reviewer that edits mid-review invalidates the fixer's accounting
   and the loop's convergence argument. It also falsifies the
   `("claude", ReadOnly, *) → Hard` row and `AgentConfig.autoApprove`'s scaladoc
   claim that the field is "only meaningful … when `tools` is `ToolSet.Full`".
3. **pi and opencode have no shell at all**, and no control-channel escape
   exists. Coordinates-only is simply unimplementable there.

The asymmetry that makes the opposite choice safe: **`Read` is available on every
read-only tier.** Anything orca puts in a file, every reviewer can get; anything
orca expects a reviewer to *compute*, only some can.

---

## 3. The measured cost of making reviewers fetch

Before #59, reviewers whose work was already committed received the literal
`"(no diff captured — review the working tree)"` and reconstructed the change set
by hand. 88 reviewer sessions of that are on disk alongside sessions that did get
a diff — a natural experiment in the variable under question.

Method: every Claude Code transcript for this project whose first user message
carries the `initial-review.md` signature, joined to the run manifests by
`wireId`; arms split on the no-diff fallback; rounds counted from
initial-review + re-review prompts; tool calls deduplicated by `tool_use`
**block id**; `StructuredOutput` excluded.

| per **round** | inline diff (n=64) | no diff (n=24) |
|---|---|---|
| API turns | **5.57** | **7.33** |
| tool calls | **5.35** | **6.80** |
| git-bearing Bash calls | **0.83** | **2.51** |

| per **session** | inline diff | no diff |
|---|---|---|
| rounds | 2.53 | 3.50 |
| API turns | 14.1 | 25.7 |
| git-bearing Bash calls | 2.1 | 8.8 |
| cost @ opus-5 rates | ~$1.45 | ~$2.64 |

Per round, withholding the diff costs **+32% turns, +27% tool calls, 3× the git
calls**. Per session the gap is larger (+82% turns) because diffless sessions
also ran **38% more rounds** — plausibly because blind reviews are worse, but
confounded (different tasks per arm). The per-round figures are the conservative
ones and the honest headline.

> **Two corrections to the first analysis.** (a) It reported "round 1 = 32.6
> tool calls, round 2 = 28.0". Those were whole-**session** totals divided by
> session count and mislabelled as rounds — each transcript spans ~5 rounds.
> Withdrawn; the correct per-round split is round 1 ≈ 10.3, round 2 ≈ 9.3,
> rounds ≥3 ≈ 3.35 tool calls per reviewer. (b) An earlier pass deduplicated by
> `message.id`; assistant records repeat that id with *different* content
> (streaming partials), so it silently drops blocks — 39 Bash calls instead of
> 199. Deduplicate by `tool_use` block id: those are unique
> (`by_tool_use_id == raw == 303`). Any figure derived by message-id dedup
> should be re-derived.

### The arithmetic

Prices from `flow/src/main/scala/orca/events/Pricing.scala:159-164`
(`claude-opus-5`): input $5/M, cache read $0.50/M, cache write $10/M, output
$25/M.

Measured over 270 deduplicated reviewer API messages in the baseline run:
cold-start prefix **32,658** tokens (confirming topic 4's preamble figure), and
per-message prefix **mean 69,948, median 66,045, max 123,615**. A reviewer's
extra turn therefore costs ≈ 70k × $0.50/M = **$0.035** in cache reads plus its
output — *not* the $0.054 implied by the run-wide 107k average, which is
dominated by the implementer at 213k.

**Why coordinates lose is a round-trip argument, not a token argument.** A
fetched diff arrives as a `tool_result`, is cache-written once and cache-read
thereafter — identically to an inlined one. The tokens are a wash. What
coordinates add is one assistant turn per fetch, plus the `status`/`log`/`--stat`
probes that precede it. Stating it as "the tokens land anyway" invites a correct
rebuttal; the delta is turns.

Coordinates could only win by fetching *less* than the whole change — exactly
what `initial-review.md:3-5` forbids ("Review the following changes only … Focus
your findings strictly on what the diff modifies").

### On the "~$2.50/run" figure

`00-research-plan.md:66` estimates the diffless rediscovery at ~$2.50/run with no
derivation; it is labelled "Estimated". Measured reviewer spend in the baseline
run is **$26.16 — 59% of the $44.64 run** — reconciling with the plan's
independently derived implementer figure ($18.17). Treat $2.50 as an unsourced
placeholder. (The same plan already revised one estimate down 7× on
measurement — T4.1.)

---

## 4. The problem T2.4 actually names: the tail

`git diff <C^> <C> -- :(top) :(exclude).orca/*` over the last 200 commits on
master (measured twice independently, identical results):

```
n=200   mean 39,835 B   median 6,196 B
p75  15,817 B (~4.0k tok)    p90  46,700 B (~11.7k tok)
p95  88,679 B (~22.2k tok)   p99  1,312,629 B (~328k tok)
max 2,104,510 B (~526k tok)
```

Observed reviewer prompts corroborate the middle: inline-diff sessions received
prompts of median ~8k chars, max ~19k.

The distribution is bimodal in consequence: the median change is trivially
inlinable, the top 1% cannot be inlined at all. The maximum is ~526k tokens on
top of a measured 32.7k preamble — several times a 200k context window.

What happens then is **unverified**: a grep for `prompt is too long`,
`context_length`, `too_long` across `claude/src/main` and `tools/src/main`
returns nothing, so orca has no handling; and `AgentCall`'s retry ladder
(`AgentCall.scala:279-283`) retries everything except `AgentTurnFailed`, so an
oversized prompt could be paid for up to four times.

**This, not cost, is the case for a cap.** A cap firing on ~4% of changes is not
a savings lever; it is a safety valve against a request that cannot be sent.

---

## 5. Prior art — the repo already has three bounded-payload policies

| where | shape | threshold |
|---|---|---|
| `flow/src/main/scala/orca/CommitDiff.scala` | **summaries first, then as much diff as fits, `…(truncated)` marker** | 8 KiB |
| `flow/src/main/scala/orca/review/Lint.scala:78-102` | all-or-nothing spill to `.orca/cache/` | 8 KiB |
| `flows/review.sc:47,123,196-209` | always spill, prompt points at the file | — |

`CommitDiff` is the decisive one. Its scaladoc (`:38-40`) states precisely the
invariant T2.4's done-when asks for: *"The summaries go first because they name
every changed file, which a truncated diff head does not."* It already handles
budget splitting, surrogate-safe cutting, and cutting path lists only between
entries. **The first analysis proposed a fourth implementation of this; that is
rejected** — AGENTS.md's "one decision, one home" (lines 277-279) applies
directly.

`flows/review.sc` is also load-bearing evidence: it ships the diff-on-disk design
with the comment *"reading the diff off disk, since a read-only reviewer cannot
produce one"*. The repo has already concluded, in shipped code, that reviewers
cannot be asked to produce their own diff.

---

## 6. Adjacent defect (being fixed separately)

`runReviewersAndLint` samples the diff only when some active reviewer lacks a
session (`ReviewLoop.scala:477-478`), and `resumeReview` (`:415-419`) takes no
diff at all — it sends the constant `re-review.md`, which carries no diff, no
issue list and no fix outcome.

Under **every shipped selector** the round-N active set is a subset of round 1's:
`agentDriven` returns the constant arrow `_ => active`
(`ReviewerSelector.scala:178`), `allEveryRound` returns `_ => all` (`:67`),
`narrowingAcrossRounds` only filters, with a floor back to that same pick
(`:191-221`). From round 2 on, every active reviewer already has a session,
`needsDiff` is false, and `sampleDiff()` is never called again.

That is a correctness bug: the resumed reviewer falls back to its own
`git diff HEAD`, empty once the work is committed, which reads as "nothing
changed". Being fixed independently; it matters here because it decides what the
re-review path should carry (§8).

---

## 7. Other unbounded inlined payloads

| site | payload | bounded? |
|---|---|---|
| `ReviewLoopPrompts.scala:58-69` | review diff × N reviewers | **no** |
| `pr/summarisePr.scala:36-37` | **entire branch-vs-base diff**, often on a `cheap` small-context model | **no** |
| `agents/Prompts.scala:107-112` (`retry.md`) | the model's whole unparseable response, echoed back — self-amplifying under retry | **no** |
| `review/FixRequest.scala:20-24` | gated review issues | LLM-bounded |
| `review/ReviewerSelectionRequest.scala` | `changedFiles` paths | mild |
| `CommitDiff` / `Lint` | commit / lint payloads | yes (8 KiB) |

`summarisePr` is the most exposed and deserves its own ticket: the same defect on
a strictly larger payload.

---

## 8. Position and plan

### Position

The instinct **does not hold up as stated**, and holds up in a form nobody
proposed.

It is a reasonable instinct — it assumes a tool call is roughly free and that the
agent knows best what it needs. Neither holds: a reviewer's tool call is a turn
against a ~70k prefix, and the reviewer's job is defined as "review all of this
change", so there is nothing to be selective about. On three of five backends the
reviewer cannot act on coordinates at all — while still answering.

What is right in it: orca should not treat the inlined diff as the only channel.
Saying *what the change set is* costs almost nothing and unlocks depth where a
shell exists.

### Plan

**T2.4-a — bound the review diff by reusing `CommitDiff`.** Generalise
`CommitDiff.payload` to take a budget and a `since` base, then call it from
`ReviewFixLoop.sampleDiff`. Named blocker: `CommitDiff.payload` takes
`PendingChanges`, and `GitTool.pendingChanges()` (`GitTool.scala:576-582`) is
hardcoded to `HEAD` — it needs the `since: Option[String]` that `reviewDiff`
already has. Do that rather than writing a parallel implementation.

**Threshold: 128 KiB, justified as a context-budget safety valve, not a
break-even.** At a measured 32.7k-token preamble, a 128 KiB diff (~32k tokens)
makes a ~65k first prompt — comfortable at 200k, impossible at 526k. Anything in
the 100–400 KB band prevents the hard failure; 128 KiB spills ~4% of this repo's
changes (8 of 200), 8 KiB would spill ~45%. *The earlier "22.6 KB / 150 KB
break-even" derivation is withdrawn: it used the run-wide 107k prefix instead of
the reviewer's 70k and omitted the recurring cache reads on the inlined text.
Corrected, break-even is nearer 10 KB per tool call avoided — which is why cost
is the wrong axis to size this on.*

**Shape: inline below the cap, summaries-plus-truncation above it**
(`CommitDiff`'s existing behaviour). If a spill file is used instead, its
lifetime must span **the whole loop**, not one round — a reviewer resumed at
round 3 may re-read the path it was given at round 1, so `Lint`'s per-call
`finally` would hand it a deleted file. Capture checking is not an obstacle:
`lint(...)` already does `ensureCache` + `os.temp` + `os.remove` from inside the
`CheckedPar` fan-out, and an `os.Path` is plain data.

**Derive the file list and line counts from `git diff --numstat -z`, never from
the diff body.** Verified in a scratch repo: `--numstat -z` yields one
NUL-delimited record per path, renames as three fields (`0\0old\0new`), binaries
as `-  -`, and **no C-quoting**, whereas plain `--numstat` quotes
(`"quo\"te.txt"`) and collapses renames to `old => new`. That is literally "every
path with its line counts" — T2.4's done-when — with no parsing of diff text.

This also repairs three real bugs in `ReviewLoop.extractChangedFiles`
(`:719-726`, regex `^\+\+\+ b/(.+)$`), all verified in a scratch repo:

- a **binary** change emits no `+++` line at all → invisible to the picker;
- a **100%-similarity rename** emits no `+++` line → a pure package move yields
  an empty `changedFiles`, sending `eligibleForPicker` down its "the diff didn't
  say" branch (fails safe, but blind);
- a **path containing a space** gets a trailing TAB captured into the path — and
  since `scala-fp.md:4` declares the anchored pattern `files: \.scala$`, a Scala
  file with a space in its path would silently drop the scala-fp reviewer.

Fix them in the same change. *One worry from the first analysis is unfounded and
should not be carried forward: splitting on `^diff --git ` at column 0 is safe
against diff-of-diff content, because every content line in a unified diff
carries a ` `/`+`/`-` prefix. Verified — it is simply unnecessary given
`--numstat -z`.*

Decide explicitly what happens to `OsGitTool.untrackedFileDiff`'s bare
`# skipped <path>: symlink to a directory` record (`GitTool.scala:635-637`),
which belongs to no section.

Test, per the done-when: **trailer ∪ rendered = the full change set**, with a
rename, a deletion and a binary file in the fixture. This test *is* the
deliverable — a valve firing on 4% of changes will otherwise never be exercised
before it matters.

**T2.4-b — add the coordinates alongside the diff.** Put the `reviewBase` SHA and
a line to the effect of "a shell may be available; use it to go deeper if a
finding needs it" into `initial-review.md`. Tens of characters; unlocks depth on
claude and codex; inert elsewhere; and it is also the stale-line fix. Do **not**
make it the only channel.

**T2.4-c (recommended, larger payoff than the cap) — per-reviewer filtered
diffs.** `ReviewerPrompts.filePatternsBySlug` already exists and `scala-fp`
declares `files: \.scala$`. Inlining the whole diff to a reviewer that only
reviews Scala pays for every non-Scala hunk, every round. Filtering the inlined
diff per reviewer is cheaper than both designs under discussion, adds no new
failure mode, and scales with roster size.

### Composition with the resumed-reviewer fix

For round 1 nothing above changes. For later rounds, **do not re-send the whole
change set** — that was the first analysis's proposal and it is its weakest part.
A new user message on a live session is a fresh cache write at $10/M *and*
permanently enlarges the prefix every later turn re-reads: a 6k-token diff to 10
reviewers over 4 extra rounds is ~240k tokens of writes ≈ **$2.40**, against
$0.60 to send it once — comparable to the entire saving this epic claims.

Preferred, in order:

1. Send the **numstat delta since that reviewer last ran**, plus the diff of just
   those files, through the same bounded renderer.
2. Failing that, send the numstat summary **plus the base SHA** and let a
   shell-capable reviewer fetch what it needs. This *is* the owner's design, and
   at round ≥2 it is genuinely competitive — small payload, fetch only on demand.
   Its defect is that pi/opencode reviewers still cannot act on it, which the
   numstat summary partly mitigates.

The increment is **not** computable from git alone: there is no snapshot of the
round-*N−1* tree, `ReviewLoopState` (`:166-171`) keeps no diff, and ADR 0018 §2.2
forbids the loop committing. The cheap fix needs no git — store the previous
round's per-file numstat in `ReviewLoopState` and send only the files whose
counts moved. Pure immutable data through the existing state record.

On whether an increment can be unbounded: `formatCommands` runs before every
round (`:562`) and this repo's setting is `format = sbt scalafmt`, which formats
all sources rather than only changed ones. But that fires at round 1's format
pass, inflating the *base* diff, not the increment; by round 2 the tree is
already formatted. A genuinely unbounded increment needs a fixer doing a
mechanical repo-wide edit — real, but rare. So apply the same bounded renderer to
both paths because it is one function, not because the re-review path drives the
requirement.

---

## 9. What is not established

1. **No post-#59 run exists on disk.** Every manifest predates the stage-base
   diff, so §3's A/B is observational (different tasks per arm), not a controlled
   trial. The round-count difference between arms is confounded.
2. **The large-diff failure mode is unverified.** No handling code exists and no
   experiment was run: which backend errors first, at what size, whether the CLI
   truncates or the API 400s, and how the retry ladder reacts are all unknown.
3. **gemini's read-only shell behaviour** is untested — no gemini transcript
   exists. codex's is inferred from its sandbox flag, not observed.
4. **Manifests cannot supply per-turn data.** All 9 are `manifestVersion: 2` and
   carry only session identity and timestamps; #61's v3 schema has never been
   exercised by a real run. Every token figure here comes from Claude Code
   transcripts joined to manifests by `wireId`.
5. **The permission-bypass finding (§2) is not fully scoped.** It was established
   for claude; whether codex/gemini/opencode/pi have an analogous gap between
   documented and actual enforcement was not checked.
