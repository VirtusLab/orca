# Flow run cost & turn hygiene — research plan

Goal: cut what a flow run costs, and remove the failure mode that makes runs
repeat themselves. Baseline is the run of 2026-08-01 (manifest
`.orca/cache/runs/1785572076039-880497.json`): **$44.64**, outcome `failed`,
21 sessions, 340 turns — plus a killed earlier run (`1785569424044-620205.json`,
**$43.60**) that did the same work and produced nothing.

Measured split of the $44 run: cache **writes** $19.36 (46%), cache **reads**
$17.16 (40%), output $5.84 (14%), uncached input $0.02. 36.5M prompt tokens
across 340 turns = **107k re-sent per turn** (implementer: 213k). Prompt caching
was already on and had already saved ~77% (uncached equivalent ≈ $188), and
orca does not control it — it shells out to the `claude` CLI, which owns
`cache_control` and TTL. So the levers are **prefix size** and **turn count**,
not caching.

Each topic produces a result file `NN-<slug>.md` in this directory. Topics 1–3
are the ones with both cost and correctness impact; 4–7 are smaller and
independent.

## Topics

### 1. Turn-boundary process teardown (`01-turn-boundary.md`)
Largest item, and a correctness bug before it is a cost bug. Orca spawns a
fresh `claude` process per turn and closes stdin
(`ClaudeBackend.scala:171-222`), so anything the agent left running dies at the
turn boundary and `--resume` replays the wreckage on the next turn. Three
measured symptoms from one cause:

- 13/13 `[Request interrupted by user]` events landed 21–63 ms after an
  orphaned-background-task notification.
- Those turns made **zero API calls** (no `requestId`, all-zero usage), so the
  "could not be parsed as JSON" retry was the turn's first billed request — the
  retry ladder itself is nearly free, contrary to first impressions.
- Every backgrounded `sbt` build was killed at teardown, so the agent never
  read a build result, reported "fixed" unverified, and the review loop ran 11
  rounds finding real problems that a verified build would have caught in one.

Questions: (a) cheap fix — a `SystemPromptComposer` rule (sibling of
`RuntimeOwnsGit`, `SystemPromptComposer.scala:19-27`) stating that background
tasks do not survive a turn, so long builds run in the foreground; orca imposes
no per-turn timeout, so a 15-minute `sbt` is fine. (b) The task brief said "run
builds in the background" — it came from an interactive-Claude-Code memory
note; where else do interactive-only assumptions leak into orca briefs?
(c) Structural fix — keep one CLI process alive per durable session, so
background work survives and `--resume` is not needed mid-run. That changes the
conversation lifecycle (`ForkedConversation`/`ClaudeBackend`) and wants its own
ADR. (d) Should an `is_error` that arrives *before* orca's prompt reaches stdin
be ignored rather than settled on? PR #45 made it self-identifying but
deliberately did not change the settle protocol. Method: codebase + transcript
archaeology, then a design pass on (c).
Estimated saving: **$8–12/run**, plus the run stops failing.

### 2. Reviewers are reviewing without a diff (`02-review-diff.md`)
`GitTool.reviewDiff()` is `git diff HEAD` plus untracked files, but the
implementer commits as it goes — so every reviewer in the baseline run received
the literal `"(no diff captured — review the working tree)"`
(`ReviewLoopPrompts.scala:60`), and each of the ten independently rediscovered
the change set with `git log` / `git show` / `git diff master...HEAD` in
**every round**. `diffVsBase(defaultBase())` already exists
(`GitTool.scala:224`). Questions: which base is right when the flow branched
from a non-default branch; what to do when the diff is genuinely large; does
handing over the diff let the re-review prompt (`re-review.md`, which today
contains neither diff nor issue list and relies wholly on session memory) get
shorter. Method: codebase, then a measured A/B on one flow.
Estimated saving: **~$2.50/run**, plus better-grounded reviews.

### 3. Durable session context growth (`03-durable-context.md`)
The implementer session was `kind: durable` and was inherited from the *killed*
run: it entered the $44 run already carrying ~178k tokens and re-read them 88
times, at an average prefix of 213k (max 249k) — **$18.17 of $42.36**.
Questions: should a durable session ever be carried across runs, or minted per
run; is compaction/context-editing available through the CLI; what does a cap
cost in re-derivation (a hard upper bound on reads alone: cap 150k → −$2.77,
cap 120k → −$4.09, cap 100k → −$4.97). Method: transcript measurement plus a
design pass.
Estimated saving: **~$4/run**.

### 4. The fixed per-session preamble (`06-preamble-measurement.md`)
Every session starts with ~32.4k tokens before any work: CLI system prompt,
tool schemas, `CLAUDE.md`, a `SessionStart` hook that injects a skill into
*every* session (including single-turn lint runs), and the 18k-char
`direct-style-scala` skill that `CLAUDE.md` mandates for every subagent. The
~32.4k holds — three independent measurements land within 1%.

The share does not. The preamble is paid **per API call, not per turn**, and
measured turns averaged 2.2 and 9.4 calls in the two measured runs, so it is
**about half of all prompt traffic** (46.7% and 53.5%), not 30%. The baseline
run's absolute is unrecoverable: its per-turn call counts were never recorded,
and the manifest only carries `apiCalls` from PR #77 on.

Question: scope the hook and the skill mandate to agents that actually write
Scala (not `lint`, not `readability`), and measure what breaks. Measured answer
so far: **no reviewer ever loaded the skill** in either run — including one
inside this repository, with the mandate in the reviewers' context — so scoping
the mandate saves nothing on its own. The cost is the call count, not the
preamble's size.
Estimated saving: **~$1.70 per 10k trimmed**, on whatever is actually loaded.

### 5. Session warmth policy (`05-session-warmth.md`)
Settled by measurement, recorded here so it is not re-litigated: **resuming a
reviewer within a stage is cheaper than a fresh seeded session** — rebuilding N
tokens of context costs the same as 20 cache reads of it, and break-even sits
at a fresh re-review costing ≤70% of an initial review, while observed
follow-up rounds ran 4.2 turns against 9.3 for round 1. Fresh-per-round would
have cost **$4.30–6.45 more**. The converse also holds: carrying a reviewer
across stages costs +$0.64 each, and the ten single-turn lint sessions each paid
a ~32k cold start to emit a few hundred tokens (~$1.37 total). Rule that falls
out: **resume when the same agent re-examines the same material within a stage;
start fresh when the material changes.** Remaining work: apply it to lint
(`ReviewLoop.scala`, `config.lint` path) and check the rule against the other
one-shot agents.
Estimated saving: **~$1.10/run**.

### 6. Cost accounting is wrong by ~45% (`06-pricing.md`)
`ModelPricing` (`Pricing.scala:19-31`) has `input` / `cachedInput` / `output`
and no cache-**write** rate, and `InboundMessage.scala:79-87` folds
`cache_creation_input_tokens` and `cache_read_input_tokens` into one counter. So
when the CLI's `total_cost_usd` is unavailable, orca prices 2.0M cache-creation
tokens at $0.50/M instead of $10/M — understating the baseline run by **$19.28
(45%)** and hiding that writes are the single largest line item. Add a cache
-write rate (or a 5m/1h pair) and split the counters. No saving; without it,
none of the above is measurable in-product.

### 7. Cache TTL (`07-cache-ttl.md`) — upstream, no orca lever
Every write in the baseline run was `ephemeral_1h` (2× write price); the 5m
tier is 1.25×. 1h only pays if >65% of written tokens would otherwise expire,
and only six of 319 inter-turn gaps exceeded five minutes (max 8.6 min — an
sbt build sits *inside* a turn, so it does not create an inter-turn gap).
Simulated 5m TTL: **$35.95 vs $42.36**. The CLI chooses the TTL and orca never
builds a request body, so this is an upstream item — recorded so the overpayment
is known, not assigned. Open question: what actually forces the 2.0M tokens of
cache creation if nothing expired, and whether topic 1's process-per-turn
teardown is the answer.

## Out of scope, but larger than all of the above

The killed run cost $43.60 and run 2 redid the same work for $44.64 — ~$43 spent
twice on one unit of work, more than every optimisation here combined. The
prevention (refuse a fresh run on a branch that already has an unfinished one)
shipped in PR #44. What remains is the other half: make a killed run **resumable**
rather than merely refused, since the manifests already record per-stage session
ids and `lastActiveAt`.
