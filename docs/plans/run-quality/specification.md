# Run quality — specification

Three analyses of six real orca runs (the console transcripts, the DEBUG
traces, and the cost accounting) found defects in what a run tells the user,
what the reviewers are given to work with, and what the cost summary claims.
This specification describes the functional changes, in five PRs.

PR 1 lands first; the rest are independent of each other.

## PR 1 — Findings: drop severity, keep location, one word for declined

Reviewers no longer label findings with a severity. Across 205 findings in six
runs the field produced zero Critical and 80% Info, two genuine defects were
filed as Info, and the fixer declined none of the 64 findings it received — so
the label never informed a decision. It follows the confidence score out for
the same reason: a self-assessed number from the model tracks the model's mood,
not the defect.

What replaces it in the one place it was doing work — the end-of-run list of
findings that were not fixed — is the finding's location. That list is the
run's permanent record of what was left open, and today it carries only a
title, so a reader cannot find the code again. It gains `file:line`.

The same list, and the lines around it, currently use three words for one
state: a finding the fixer chose not to fix is "ignored" in one place,
"unresolved" in another, and implied by "no issues to fix" in a third — which
prints even when findings are still open. One word, `declined`, is used
throughout, and the headline states what is actually true.

## PR 2 — What the reviewers work with

**The re-review payload.** After a fix, a reviewer is meant to check the fix.
Today, when the change set exceeds 16 KiB, it is instead handed a list of
filenames and told to read them. That threshold is crossed by 10 of 14 task
diffs measured, and by every whole-run diff (80–118 KB), so the final review
introduced by the previous rework is degraded in every round after its first.
The reviewer is sent the changed parts of the diff itself, bounded so a resumed
conversation cannot accumulate a large diff per round.

**Review scope.** The reviewer instructions say to focus strictly on what the
diff modifies, and the re-review instructions repeat it. That wording is why
the largest defect found in these runs was missed: a reviewer read the
offending lines and said nothing, because they were not part of the diff. Both
sets of instructions are changed: reviewers name the assumptions a change
relaxes and check the unchanged code that relies on them — unchanged code is in
scope when the change alters what it can be handed.

**Rounds.** The final review's cap drops from 5 to 3. Removing the confidence
gate widened what counts as an active reviewer, so per-round narrowing now
retires far fewer of them; five rounds over a whole-run diff with little
narrowing is the most expensive thing in a run and the last rounds yield least.

**`simple.sc`.** The flow reviews with a single general-purpose reviewer,
chosen to avoid a picker round-trip that costs eleven cents. Two changes
developed through it shipped defects of exactly the kind the roster catches. It
uses the full roster and the standard picker.

**Prompt logging.** What each reviewer and the fixer was actually sent is not
recorded anywhere, so no post-mortem can establish what a reviewer saw. The
rendered prompts, and each reviewer's diff payload and its form, are logged to
the run's debug trace.

## PR 3 — What the run shows while it works

**Less noise.** Read-only tool calls no longer get a line each — they are half
of all output, and orca already has a live status line for liveness and a trace
file for detail. A reviewer's closing prose message is dropped when the
structured result that follows says the same thing better; mid-turn narration
stays. Truncation budgets account for indentation and agent labels, so a line
meant to be one line is one line, and a long path keeps its filename.

**Failures.** The user gets the actual cause of a failure on one line,
attributed to the agent that failed rather than appearing as an unlabelled
repeat of the stage error.

**Consistency.** The glyph table in ADR 0008 is corrected to match the code.
Orca's own bookkeeping commits stop appearing as if they were the user's work,
and the process-sweep warning stops printing a JDK path.

## PR 4 — What the run tells the user at the end

**A closing summary.** A run currently ends with a bookkeeping commit line and
a cost table. It gains a closing block naming the branch the work is on, the
files changed, and the next command to run. Every run states its branch,
including a resumed one.

**Resume.** A resumed run says what it is resuming from and that the
interrupted stage's uncommitted work was discarded, once, instead of printing
each replayed stage's name twice. The coder is told the same thing, so it does
not report work it can no longer see as done. A failure ends by saying that
re-running the same command resumes from the last completed stage.

## PR 5 — What the cost summary claims

**Attribution.** The per-agent and per-role breakdowns cannot separate planning
from coding, because the shipped flows leave every role agent named `main` and
cheap sub-calls inherit that name. The role agents are named, so the breakdown
answers what planning, coding and review each cost. The reviewer-picker call is
attributed to review.

**Detail.** The summary prints three breakdowns of the same turns, with cache
read/write splits, at the moment the user wants a verdict. It prints the total,
the split by role and the split by model; the per-agent detail is recoverable
from the run's cost log.

**The persisted log.** That log omits the model, so the by-model breakdown
cannot be reproduced from it, against the file's own stated contract. It
records the model. The price table's missing entry is added.

## Out of scope

Verifying the fixer's own output — neither the files it edits nor the files it
creates get a second reviewer pass at task level; the final review remains the
only check on them.

Reporting turns that were cancelled mid-flight. An interrupted run's total
silently excludes agent turns killed before their usage arrived (measured at
$2–3 on one run), and the summary prints a bare total regardless. The figure
stays a floor with nothing saying so.

Counting findings in the closing summary. A run has several review loops, and
the loops do not distinguish a finding the fixer declined from one left open at
the round cap, so any run-level tally would be both double-counted and
mislabelled. The per-loop announcements already state each loop's outcome.

Merging findings that several reviewers report at the same `file:line` into
one entry for the fixer. It was specified here and then measured against the
same six runs. The "sixth of all findings are duplicates" figure that motivated
it was a misreading: 26 of those 34 findings are one reviewer re-filing its own
finding in a later round, which the rule excludes by design and cannot see. The
real population is 4 cross-reviewer clusters — 8 findings out of 205, about 1.3
per run. The rule nonetheless fires on 42 findings (20.5% of all of them), and
about 40% of those merges join two *different* problems and silently drop the
second's description and suggestion, several of them behavioural warnings. The
cause is structural: reviewers anchor a finding to the definition line of
whatever they are discussing, so "no test for X", "X has a bug" and "X is in
the wrong file" all land on the same line. The saving it bought was ~700 tokens
per run.

Folding declines whose titles are genuinely reworded between rounds. An exact
rule cannot catch a rewording, and a fuzzy one must not: wrongly merging two
findings drops one from the permanent record.

A guaranteed reviewer-selection floor (the two reviewers it would force found
nothing in these runs); moving the documentation beat between reviewers;
running any reviewer on a cheaper model; giving reviewers the ordered task list
or execution tools.
