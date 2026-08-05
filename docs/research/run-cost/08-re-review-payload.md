# T2.5 — Should the re-review prompt carry more payload?

Investigates whether `re-review.md` should carry the fixer's `FixOutcome`, a
diff, or anything else beyond the eight lines it sends today. Read-only: no code
changed.

Source read at `59597ca5`. Every measurement below is over the baseline run of
2026-08-01 (`.orca/cache/runs/1785572076039-880497.json`) and the ten non-lint
reviewer transcripts it produced.

The short answer: **send the fixer's `ignored` list, not its `fixed` list, and
prioritise a diff over both.**

**Open PRs this document is written against.** #72 (resumed-reviewer diff) is
open and changes the ground under §1, §5 and §6(c); the markers below say what
becomes stale if it lands first, and are correct whichever order is taken.

## 1. What the re-review prompt carries today

`ReviewLoopPrompts.ReReview` (`ReviewLoopPrompts.scala:75-76`) is
`PromptResource.load`, not `render` — the resource has no placeholders and
nothing is interpolated into it. The whole payload is:

> Fixes have been applied to the working tree based on your earlier review.
> Re-review the current state — focus on whether your earlier findings were
> addressed and on any new issues introduced by the fix. Stay scoped to the same
> changes you reviewed initially; do not expand to unrelated files. If nothing in
> your scope still applies, report no issues.
>
> The confidence contract from the initial prompt still applies: the probability
> the finding is real, on the evidence you gathered — not deference to the plan.

`resumeReview` (`ReviewLoop.scala:415-419`) sends exactly that text plus the
structured-output schema preamble. No diff, no issue list, no fix outcome, no
list of files touched.

### The resumed reviewer receives no diff at all — including after #59

> **Stale if #72 lands first.** This whole section describes `59597ca5`. PR #72
> makes `runReviewersAndLint` sample whenever any reviewer runs and passes the
> result to `resumeReview`, so the defect below is fixed and §1 becomes a record
> of what was true before it. Read §1 as history from that point on; §2, §3, §4
> and §7 are unaffected.

`runReviewersAndLint` samples the diff only when some active reviewer has no
session yet:

```scala
val needsDiff = active.exists(e => storedFor(e).isEmpty)   // ReviewLoop.scala:477
val currentDiff = if needsDiff then sampleDiff() else ""   // ReviewLoop.scala:478
```

Under every shipped selector, `needsDiff` is false from round 2 onward:

- `agentDriven` computes its pick once and returns the constant arrow
  `_ => active` (`ReviewerSelector.scala:178`);
- `narrowingAcrossRounds` only *filters* that pick, and its floor falls back to
  the same pick rather than to the roster (`ReviewerSelector.scala:203-221`);
- `allEveryRound` returns `all` every round (`ReviewerSelector.scala:67`).

So the active set at round N is always a subset of round 1's, every member of
which already holds a session. The re-sampled diff never reaches anybody.

The consequence for this task: **#59 changed round 1 and left follow-up rounds
untouched.** The scaladoc's "a reviewer joining the active set on iteration N
sees the earlier fixes too" (`ReviewLoop.scala:262-266`, `:374-376`) describes a
path only a custom selector can reach. The premise the T2.5 hypothesis was built
on — reviewers rediscovering the change set by hand every round — is still
exactly true from round 2 on.

Two smaller observations on the prompt resources:

- `initial-review.md:7` still labels its payload "Diff (working tree vs HEAD at
  the start of the review loop)". #59 made that false: the diff is now
  stage-base-relative. T2.2's done-when asked for this line and it is unfixed.
- `select-reviewers.md:8` still tells the picker to try `git diff HEAD`, which
  is the sampling decision #59 abandoned.

> Both are corrected by #72; if it lands first, these two bullets are done.

## 2. Is the fix outcome absent, or merely implicit?

Genuinely absent. Traced end to end:

- produced at `ReviewLoop.scala:606-612` (`coderSession.resultAs[FixOutcome]`);
- consumed in `run()` only as `outcome.fixed.size` / `outcome.ignored` for the
  display line and the accumulated `IgnoredIssues` (`ReviewLoop.scala:698-711`),
  and by `unaccountedFor` at the halt exit (`:633-644`);
- `fixLoop`'s state-free variant does the same (`:92-99`).

No prompt resource interpolates it — `fix.md` is the fixer's own brief, not
anything a reviewer sees. A grep for `FixOutcome` across `flow/src/main` returns
only these sites plus the type's own definition.

The only trace that escapes the loop is `orca.display("Fixed N, ignored M")` —
counts, no titles — and that goes to the event log, not into any agent's prompt.
Reviewer sessions are minted with a bare `e.agent.withRole(...).chat()`
(`ReviewLoop.scala:429`), with no seed and no progress preamble, so nothing
reaches them by that route either.

## 3. What the resumed session can be relied on to hold

It is a `--resume` of the reviewer's own CLI session, so it holds:

- its system prompt (the reviewer's identity);
- the round-1 `initial-review` prompt **including the round-1 diff**;
- every tool call and result it made in earlier rounds;
- its own earlier findings, verbatim, as structured output.

It does **not** hold, and cannot derive without spending tool calls:

- what the fixer did, or claimed to do, about any finding;
- the fixer's reason for declining a finding — not recoverable from the tree at
  any price;
- which files changed since its last round.

> The third item is what #72 supplies, for the whole change set rather than the
> per-reviewer increment. The first two are unaffected by it.

Two reliability caveats on "session memory", both observed:

- **Context compaction.** Not observed in the baseline (zero compaction events
  across the ten reviewer sessions), but those loops ran 3–5 rounds. Nothing in
  the baseline came close to the window: the largest single reviewer prompt
  measured **123,615** tokens, at round 5 of the longest loop, against a 200k
  window. (The 399k in §4's round-1 row is the *sum* of cache reads across that
  round's ~10 API messages, not a context size — per-message prefix in round 1
  averaged 47.0k. It is not evidence about compaction.) The shipped
  `maxIterations = 10` permits 11 rounds, and per-message prefix grew
  monotonically across the observed rounds (47.0k → 68.7k → 96.7k mean), so a
  full-length loop plausibly reaches the window — but that is an extrapolation
  from five rounds, not an observation, and at compaction the round-1 diff
  becomes a summary.
- **Resume leftovers.** `No response requested.` appears 30 times across the ten
  reviewer transcripts — the T1.3 pre-prompt `result` defect, live inside
  reviewer sessions specifically.

## 4. What a follow-up round costs

**No post-change measurement exists.** Every manifest under `.orca/cache/runs/`
is `manifestVersion: 2`; #61's schema v3 has not been exercised by a run yet, so
there is no per-turn record for the current code. Nothing below measures today.

What follows is the baseline run of 2026-08-01 (`outcome: failed`), reconstructed
from the ten reviewer transcripts it produced — two tasks, ten reviewer sessions,
40 review rounds — under the pre-#59/#48 regime.

**Two different dedup keys, one per column kind.** The CLI writes one transcript
line per content block and repeats the same `usage` on each, so the two axes
cannot share a key:

- **Tool calls** are unique `tool_use` **block ids**, `StructuredOutput`
  included. Block ids are unique — 303 raw blocks, 303 distinct ids — so this is
  a plain count. Deduplicating tool calls by assistant *message* id instead
  yields 52, because the streaming partials that share a message id carry
  different content and all but the first are dropped.
- **Token columns** are deduplicated by assistant **message id**, keeping one
  `usage` per message: 300 distinct assistant messages, of which 270 carry a
  non-zero prompt (the other 30 are the `No response requested.` records
  below). Summing per block would roughly triple the totals.

| | n | tool calls (mean / median) | LLM messages | cache write | cache read | output | est. $/reviewer |
|---|---|---|---|---|---|---|---|
| round 1 (initial) | 10 | 11.3 / 9.5 | 10.3 | 37.3k | 399k | 7.2k | 0.75 |
| round 2 (first re-review) | 10 | 10.3 / 10.5 | 10.3 | 63.2k | 576k | 8.9k | 1.14 |
| rounds ≥3 | 20 | 4.35 / 4.0 | 4.7 | 9.1k | 397k | 2.8k | 0.36 |

Dollar figures are estimates at the shipped `claude-opus-5` card
(`Pricing.scala:159-164`; write $10/M, read $0.50/M, output $25/M) — the CLI's
own `total_cost_usd` is not recorded per assistant message. The three rows sum to
~$26 across these ten sessions against a $44.64 run, which puts the
reconstruction in the right range. That is a sanity check, not a reconciliation:
an estimate at card rates and the CLI's own billed total are not the same
quantity.

### Reconciliation with T2.4 (#73)

**These tables do not disagree.** #73 §3 reports the same three rounds as 10.3 /
9.3 / 3.35 tool calls — exactly 1.00 below each row here, in all three rounds.
The reason is inclusion, not measurement: both count unique `tool_use` block
ids over the same 40 rounds, and this document includes the mandatory
`StructuredOutput` call while #73 excludes it. The rows here sum to **303**
blocks (199 `Bash`, 63 `Read`, 40 `StructuredOutput`, 1 `Skill`); #73's sum to
**263** = 303 − 40, one `StructuredOutput` per round. Either convention is
defensible.

**What the calls are spent on.** Every `Bash` command in the round, sorted into
the two categories that matter for this question. The columns are **not
exhaustive** — they name git-reconstruction and code-reading, and leave out
what falls in neither, so they sum to 189 of the 199 `Bash` calls. Round totals
are given so the percentages below have a stated denominator.

| | git status/diff/log/show | read & search (rg, grep, sed, …) | all `Bash` calls |
|---|---|---|---|
| round 1 | 39 | 35 | 76 |
| round 2 | 21 | 41 | 67 |
| rounds ≥3 | **38** | 15 | 56 |

Round 2 is dominated by reading code. Rounds ≥3 invert: **68% of shell calls are
reconstructing what changed** — 38 of the round's 56 `Bash` calls — at ~1.9 git
calls per round out of 4.35 tool calls, one of which is the mandatory
`StructuredOutput` call. The floor for a round that verifies nothing is 2 calls;
the observed minimum is 2.

A representative round in full (the `performance` reviewer, round 3 of task 1):

```
Bash  git status --porcelain=v1 && echo "---" && git diff HEAD --stat
Bash  git diff HEAD -- runner/.../FlowLifecycle.scala flow/.../ProgressScan.scala
TEXT  "Re-reviewed the current working tree. The only change since my last pass
       is in FlowLifecycle: busyBranchHeader became busyBranchLog …"
StructuredOutput  {"issues": []}
```

Two of three calls reconstruct a change orca already knows, and the reviewer says
what it wanted: *the change since its last pass*.

## 5. Does the original 9.3-vs-4.2 evidence survive?

**It reproduces and it survives, though not for the expected reason, and it is
mis-grouped.**

- *Reproduces.* Round 1's median of 9.5 tool calls and rounds ≥3's mean of 4.35
  match the 9.3/4.2 figures closely enough that they are plainly the same
  measurement.
- *Mis-grouped.* Splitting "follow-up" at round 2 hides that round 2 is the most
  expensive round in the loop — 10.3 tool calls and, because it re-reads the
  fixer's edits into a grown prefix, ~$1.14 per reviewer against round 1's ~$0.75.
  "Follow-up rounds are cheap" is true only from round 3.
- *Survives #59.* The brief expected it not to. But #59 cannot reach a resumed
  reviewer (§1), so the rounds-≥3 figure describes today's code as well as it
  described the baseline. Round 1 should fall — it now gets a real diff instead
  of `(no diff captured)` — and round 2 may fall a little, since the round-1 diff
  is now in session. Round 3 onward should not move at all.
  > **Stale if #72 lands first.** This bullet holds only while the resumed
  > reviewer gets no diff. #72 gives it one every round it changed, which is the
  > intervention the rounds-≥3 figure measures the absence of, so that figure
  > then describes the pre-#72 code and has to be re-measured. Nothing else in
  > §5 depends on it.
- *Survives #48, with a caveat.* Narrowing changes *how many* reviewers run in
  later rounds, not what each one does. The per-reviewer figure stands; the
  per-round aggregate falls by whatever fraction goes quiet. In the baseline's
  first task, all five reviewers ran all five rounds; under narrowing that set
  would have shrunk.

So: treat 4.2 tool calls per reviewer per follow-up round as still live — *until
#72 lands*, at which point it becomes a pre-#72 figure and needs re-measuring.
Treat any *aggregate* extrapolated from it as stale either way.

## 6. Recommendation

**(a) Do not send the `fixed` titles — as the default, not as a settled
refusal.** The argument below is a design argument, and the experiment that
would test it is §8's `+fixed` arm. Nothing here measures rubber-stamping; the
recommendation is what to ship *pending* that measurement, and it should be
revisited if the arm comes back clean. Three reasons, in order of weight:

1. It answers the question the round exists to ask. A reviewer told "your finding
   was fixed" is handed the conclusion it was asked to reach on its own.
   `initial-review.md:35-40` already has a section — "The plan is
   not evidence" — establishing that a claim about the code is not evidence about
   the code. A fixer's `fixed` list is the same class of claim, from an agent
   with an interest in the answer.
2. It endangers two properties the loop depends on. The confidence contract —
   stated in `initial-review.md:17-22` and pinned schema-side at
   `ReviewIssue.scala:23-32` — asks for "your estimated probability … judged
   only on the evidence you gathered by reading the code and tracing its
   behaviour". A prior from the fixer corrupts that number in a way
   `ConfidenceGate` cannot detect, because a confident rubber stamp and a
   confident verification are the same float. And the shipped selector's
   rationale — "it's the reviewer that reported which has to check the fix it
   triggered" (`ReviewerSelector.scala:52-53`) — is precisely the check being
   softened. (ADR 0011 is the *roster* decision and says nothing about the
   confidence contract; an earlier draft of this section cited it and was
   wrong.)
3. It saves nothing measurable. The reviewer must open the code either way; a
   title tells it neither which file to open nor what changed. It removes zero of
   the 1.9 git calls per round.

**(b) Do send the `ignored` titles with their reasons.** The case here is the
reverse. It is small (typically 0–3 entries, ~50–150 tokens), and it
is the one thing in the loop that is **not recoverable from the tree at any
price**: no amount of reading tells a reviewer that the fixer considered a
finding a deliberate trade-off. Today the reviewer re-reports the finding, the
fixer re-declines it, and the round is spent — and because the loop keeps
iterating while `fixed` is non-empty, that exchange can repeat for the life of
the loop. Frame it as the fixer's position, not a verdict: *"the fixer declined
these, with its stated reason; if you still think the finding is real, report it
again and say why the reason is wrong."* That keeps disagreement possible, which
is what makes it safe.

**(c) The larger lever is a diff, not the fix outcome.** Hand the resumed
reviewer the change since its own previous round. That is what 38 of 56 late-round
shell calls reconstruct by hand, and what the exemplar round spends two of three
calls on. Unlike `fixed`, a diff is evidence rather than a claim, so it does not
touch the confidence gate at all — it is the same kind of payload round 1
already gets.

> **Partly shipped by #72, in the coarser shape.** #72 sends the resumed
> reviewer the whole re-sampled change set rather than the per-reviewer
> increment, classified against what that reviewer was last sent
> (`AlreadySeen` → nothing, `TooLarge` → paths only above 16 KiB, otherwise the
> diff inline). That takes the correctness half of (c) and most of the cost
> half. What remains open is the increment itself, which is strictly smaller and
> which #72 explicitly defers. Note also that #72's arrival re-orders this
> document against the rule stated under **Ordering** below.

It also closes a live correctness hole, not just a cost one. A resumed reviewer
falls back to its own `git diff HEAD`, which is **empty the moment the fixer
commits** — the exact failure #59 removed from round 1 and left standing on the
follow-up path. `RuntimeOwnsGit` (`SystemPromptComposer.scala:31-32`) tells the
fixer not to commit and was in force during the baseline, where the work was
committed anyway. A reviewer whose `git diff HEAD` comes back empty concludes
nothing changed and reports clean; nothing downstream can tell that apart from a
real all-clear.

**Mechanism note — checked, and the sketch does not work.** (git 2.53, scratch
repo, 2026-08-05.)

The sketch was: a tree written through a throwaway index (`GIT_INDEX_FILE=<tmp>
git add -A && git write-tree`) gives a per-round snapshot without touching HEAD,
the real index or the working tree, and `reviewDiff(Some(previousTree))` then
reads as "everything that changed since your last round". The snapshot half is
fine. The `reviewDiff` half is not:

- `git diff <tree>` walks the **real index** to decide what is tracked, so a
  file that is untracked there but present in the snapshot comes out as a
  **deletion**. Measured: snapshot taken with an untracked `untracked.txt` in
  it, working tree then untouched, `git diff <tree>` reports `D untracked.txt`.
- `reviewDiff` also splices every current untracked file in as a new-file diff
  (`GitTool.scala:573-574`), so the same file arrives a second time, in full.

A resumed reviewer would therefore be told, every round, that each new file was
deleted and re-added. Ignore rules — the thing this note flagged as unchecked —
are not the problem: `git add -A` and `untrackedPaths()` (`git status -uall`,
no `--ignored`) both skip ignored files, so those two do agree.

What does produce the increment is a **tree-to-tree** diff between two
snapshots. Measured: `git diff <prevTree> <nowTree>` reports exactly the
modified tracked file and the added untracked one, no phantom deletion, and it
renders an added file in full, so no splice is needed at all. That is a
different mechanism from the one above, and a bigger change than "a `GitTool`
addition": the snapshot has to carry orca's `.orca/` exclusion pathspec, `git
add -A` into an empty index re-hashes the whole working tree every round
(seeding the temp index from the real one avoids that), it writes blobs into the
object store from a path that is otherwise read-only, and `SessionEntry` has to
carry a tree per reviewer instead of the diff text it compares today. Not
attempted.

**Ordering.** (b) is a prompt-and-plumbing change of a few lines and can ship on
its own. (c) needs a `GitTool` addition and touches `ReviewLoopState`, so it
belongs with Epic 2's remaining work — and it must not ship before T2.4 decides
the diff-size policy, since an uncapped per-round delta can cost more than the
two tool calls it removes.

> **This rule conflicts with the open PRs, and the conflict is not resolved
> here.** #72 ships the round-≥2 change set before T2.4 (#73) is merged, which
> is the order this paragraph says not to take. The mitigation #72 carries is
> its own 16 KiB `TooLarge` threshold, which bounds the per-round payload
> without settling the general policy — and #72 says so, leaving the initial
> diff uncapped. Whether that is enough is a call for whoever merges, not
> something this document should decide retroactively: recorded as a conflict
> rather than silently resolved.

## 7. The risk to review quality

- **Rubber-stamping** is the real risk, and it is why (a) is a default. A
  reviewer that clears a finding because it was told the
  finding was fixed produces the same output as one that verified — the loop
  cannot distinguish them, and the failure mode is silent approval of unfixed
  code. **This risk is argued, not measured**; §8's `+fixed` arm is what would
  measure it. Epic 1's "agents reporting 'fixed' on builds they never saw" is
  *not* an instance of it: that is the fixer, not a reviewer, and the cause is
  the turn boundary killing its backgrounded build, not a claim it was handed.
  It shows the loop cannot detect an unverified "fixed" — which is why the risk
  matters — but it is not evidence that telling a reviewer about `fixed` titles
  produces one.
- **Attention narrowing** is the risk in (c). A reviewer handed "here is what
  changed since your last round" may stop checking whether its *earlier* finding
  is still open somewhere the delta does not touch. Mitigation is wording, not
  mechanism: the delta is additional context, the round-1 diff stays in session,
  and the prompt should say earlier findings stand until the reviewer can see
  they were addressed.
- **Token cost** in (c) is unbounded today, exactly as T2.4 records for the
  initial diff. A fixer that reformats a file sends a delta larger than the round
  it was meant to shrink.
- **(b)'s risk is mild but real**: a reviewer may treat the fixer's reason as
  settling the matter. The wording above is what keeps it from doing so, and
  unlike (a) the underlying fact is one the reviewer could not have obtained by
  working harder.

## 8. What would settle the rest

Nothing here measures the current code, and nothing can until T0.3's checked-in
benchmark flow exists — run-to-run variance in a real task exceeds every effect
in this document. With that flow and a v3 manifest, one experiment settles all
three questions at once:

**Three arms over the same seeded flow**, identical in everything but
`re-review.md`: control · `+ignored`-with-reasons · `+delta-diff`.

- *Cost*, from the manifest alone: per-reviewer follow-up-round turn count and
  `promptTokens`, split at round 2 versus rounds ≥3 (the split this document
  shows the earlier figures blurred). Predicted: the delta arm removes ~2 tool
  calls per reviewer from rounds ≥3 and nothing from round 2.
- *Convergence*, for the `ignored` arm: rounds to termination, and the count of
  findings re-reported after being declined. If the reasons work, both fall.
- *The harm* — the measurement §6(a) is pending, and the reason for a fourth
  `+fixed` arm even though §6(a)'s default is against shipping it:
  count how often a reviewer clears one of its own findings **in a round where
  the file it named did not change**. That is a rubber stamp with no innocent
  explanation, it is computable from the delta diff plus the round's issue lists,
  and it is the only signal that separates a cheaper loop from a blinder one. If
  the `+fixed` arm's rate rises above control's, the refusal in §6(a) is
  confirmed rather than merely argued.

Two things the experiment will not settle, and that need judgement instead: how
large a real fixer delta is in a repo like this one (a T2.4 input), and whether
compaction ever eats the round-1 diff in a loop that runs to the shipped
`maxIterations = 10`.
