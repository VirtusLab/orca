# T2.5 — Should the re-review prompt carry more payload?

Investigates whether `re-review.md` should carry the fixer's `FixOutcome`, a
diff, or anything else beyond the eight lines it sends today. Read-only: no code
changed.

The short answer: **send the fixer's `ignored` list, not its `fixed` list, and
prioritise a diff over both.** The reasoning, and the measurement behind it,
follow.

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

This is the finding that reframes the rest. `runReviewersAndLint` samples the
diff only when some active reviewer has no session yet:

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

Two smaller observations while in the prompt resources:

- `initial-review.md:7` still labels its payload "Diff (working tree vs HEAD at
  the start of the review loop)". #59 made that false: the diff is now
  stage-base-relative. T2.2's done-when asked for this line and it is unfixed.
- `select-reviewers.md:8` still tells the picker to try `git diff HEAD`, which
  is the sampling decision #59 abandoned.

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

Two reliability caveats on "session memory", both observed:

- **Context compaction.** Not observed in the baseline (zero compaction events
  across the ten reviewer sessions), but those loops ran 3–5 rounds. The shipped
  `maxIterations = 10` permits 11 rounds on an Opus session already carrying
  ~400k tokens of prefix; at compaction the round-1 diff becomes a summary.
- **Resume leftovers.** `No response requested.` appears 30 times across the ten
  reviewer transcripts — the T1.3 pre-prompt `result` defect, live inside
  reviewer sessions specifically.

## 4. What a follow-up round costs

**No post-change measurement exists.** Every manifest under `.orca/cache/runs/`
is `manifestVersion: 2`; #61's schema v3 has not been exercised by a run yet, so
there is no per-turn record for the current code. Nothing below measures today.

What follows is the baseline run of 2026-08-01 (`outcome: failed`), reconstructed
from the ten reviewer transcripts it produced — two tasks, ten reviewer sessions,
40 review rounds — under the pre-#59/#48 regime. Counts are deduped by assistant
message id: the CLI writes one transcript line per content block and repeats the
same `usage` on each, so a naive sum roughly triples the tokens.

| | n | tool calls (mean / median) | LLM messages | cache write | cache read | output | est. $/reviewer |
|---|---|---|---|---|---|---|---|
| round 1 (initial) | 10 | 11.3 / 9.5 | 10.3 | 37.3k | 399k | 7.2k | 0.75 |
| round 2 (first re-review) | 10 | 10.3 / 10.5 | 10.3 | 63.2k | 576k | 8.9k | 1.14 |
| rounds ≥3 | 20 | 4.35 / 4.0 | 4.7 | 9.1k | 397k | 2.8k | 0.36 |

Dollar figures are estimates at the shipped `claude-opus-5` card
(`Pricing.scala:159-164`; write $10/M, read $0.50/M, output $25/M) — the CLI's
own `total_cost_usd` is not recorded per assistant message. The three rows sum to
~$26 across these ten sessions against a $44.64 run, which is the sanity check
that the reconstruction is in the right range.

**What the calls are spent on** — every `Bash` command, classified:

| | git status/diff/log/show | read & search (rg, grep, sed, …) |
|---|---|---|
| round 1 | 39 | 35 |
| round 2 | 21 | 41 |
| rounds ≥3 | **38** | 15 |

Round 2 is dominated by reading code. Rounds ≥3 invert: **68% of shell calls are
reconstructing what changed**, at ~1.9 git calls per round out of 4.35 tool calls
— one of which is the mandatory `StructuredOutput` call. The floor for a round
that verifies nothing is 2 calls; the observed minimum is 2.

A representative round in full (the `performance` reviewer, round 3 of task 1):

```
Bash  git status --porcelain=v1 && echo "---" && git diff HEAD --stat
Bash  git diff HEAD -- runner/.../FlowLifecycle.scala flow/.../ProgressScan.scala
TEXT  "Re-reviewed the current working tree. The only change since my last pass
       is in FlowLifecycle: busyBranchHeader became busyBranchLog …"
StructuredOutput  {"issues": []}
```

Two of three calls reconstruct a delta orca already knows, and the reviewer's own
words name what it wanted: *the change since its last pass*.

## 5. Does the original 9.3-vs-4.2 evidence survive?

**It reproduces, and it survives — for the wrong reason — but it is
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
- *Survives #48, with a caveat.* Narrowing changes *how many* reviewers run in
  later rounds, not what each one does. The per-reviewer figure stands; the
  per-round aggregate falls by whatever fraction goes quiet. In the baseline's
  first task, all five reviewers ran all five rounds; under narrowing that set
  would have shrunk.

So: treat 4.2 tool calls per reviewer per follow-up round as still live. Treat
any *aggregate* extrapolated from it as stale.

## 6. Recommendation

**(a) Do not send the `fixed` titles.** Three reasons, in order of weight:

1. It answers the question the round exists to ask. A reviewer told "your finding
   was fixed" is being handed the conclusion it was convened to reach
   independently. `initial-review.md:35-40` already has a section — "The plan is
   not evidence" — establishing that a claim about the code is not evidence about
   the code. A fixer's `fixed` list is the same class of claim, from an agent
   with an interest in the answer.
2. It endangers the two properties ADR 0011 depends on. The confidence gate wants
   "your estimated probability … judged only on the evidence you gathered by
   reading the code"; a prior from the fixer corrupts that number in a way the
   gate cannot detect, because a confident rubber stamp and a confident
   verification are the same float. And the shipped selector's rationale — "it's
   the reviewer that reported which has to check the fix it triggered"
   (`ReviewerSelector.scala:52-53`) — is precisely the check being softened.
3. It saves nothing measurable. The reviewer must open the code either way; a
   title tells it neither which file to open nor what changed. It removes zero of
   the 1.9 git calls per round.

**(b) Do send the `ignored` titles with their reasons.** This is the opposite
case on every axis. It is small (typically 0–3 entries, ~50–150 tokens), and it
is the one thing in the loop that is **not recoverable from the tree at any
price**: no amount of reading tells a reviewer that the fixer considered a
finding a deliberate trade-off. Today the reviewer re-reports the finding, the
fixer re-declines it, and the round is spent — and because the loop keeps
iterating while `fixed` is non-empty, that exchange can repeat for the life of
the loop. Frame it as the fixer's position, not a verdict: *"the fixer declined
these, with its stated reason; if you still think the finding is real, report it
again and say why the reason is wrong."* That keeps the disagreement possible,
which is what makes it safe.

**(c) The larger lever is a diff, not the fix outcome.** Hand the resumed
reviewer the change since its own previous round. That is what 38 of 56 late-round
shell calls reconstruct by hand, and what the exemplar round spends two of three
calls on. Unlike `fixed`, a diff is evidence rather than a claim, so it does not
touch the confidence gate at all — it is the same kind of payload round 1
already gets.

It also closes a live correctness hole, not just a cost one. A resumed reviewer
falls back to its own `git diff HEAD`, which is **empty the moment the fixer
commits** — the exact failure #59 removed from round 1 and left standing on the
follow-up path. `RuntimeOwnsGit` (`SystemPromptComposer.scala:31-32`) tells the
fixer not to commit and was in force during the baseline, where the work was
committed anyway. A reviewer whose `git diff HEAD` comes back empty concludes
nothing changed and reports clean; nothing downstream can tell that apart from a
real all-clear.

Mechanism note, unverified: `reviewDiff(since)` already accepts any commit-ish
and already splices untracked file contents (`GitTool.scala:573-574`), so the
missing piece is a per-round snapshot to diff against. A tree object written
through a throwaway index (`GIT_INDEX_FILE=<tmp> git add -A && git write-tree`)
gives one without touching HEAD, the real index, or the working tree, and
`reviewDiff(Some(previousTree))` then reads as "everything that changed since
your last round". Whether ignore rules make that snapshot agree with
`untrackedPaths()` was not checked.

**Ordering.** (b) is a prompt-and-plumbing change of a few lines and can ship on
its own. (c) needs a `GitTool` addition and touches `ReviewLoopState`, so it
belongs with Epic 2's remaining work — and it must not ship before T2.4 decides
the diff-size policy, since an uncapped per-round delta can cost more than the
two tool calls it removes.

## 7. The risk to review quality, stated plainly

- **Rubber-stamping** is the real risk, and it is why (a) is a refusal rather
  than a "measure it first". A reviewer that clears a finding because it was told
  the finding was fixed produces the same output as one that verified — the loop
  cannot distinguish them, and the failure mode is silent approval of unfixed
  code. That is the outcome the baseline run already reached by another route
  (agents reporting "fixed" on builds they never saw, per Epic 1).
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
- *The harm*, which is the measurement that actually matters and is the reason
  to add a fourth `+fixed` arm even though §6(a) recommends against shipping it:
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
