# Review rework — specification

A post-mortem of an `implement.sc` run showed the review loop missing real
defects: findings raised by reviewers were silently dropped by the confidence
gate, reviewers deferred to the plan, cross-task interactions were never
reviewed, and relevant reviewers were never selected. This specification
describes the functional changes, split into four PRs. Landing order:
PR 2 → PR 1 → PR 3 → PR 4. One combined ADR records all four.

## PR 2 — Drop the confidence machinery

Reviewers no longer attach confidence scores, and no gate filters their
findings. A reviewer reports a finding only when it believes the finding
should be fixed; every reported finding reaches the fixer. The fixer may
decline a finding with a reason; declines are shown to reviewers in later
rounds (who may re-report with a rebuttal) and reported at the end of the
run. Nothing is ever silently dropped.

Severity labels stay (they order and label reports). Reviewer narrowing
across rounds now keys on what a reviewer actually reported, so a reviewer
is retired only on genuine silence — never because its findings were filtered.

No damping rule for repeated declines: the round cap and the
nothing-was-fixed halt already bound re-litigation. Revisit if real runs
show token waste on stubborn disagreements.

## PR 1 — Review shape: single pass per task, whole-branch final review

Per-task review stops being a loop. A new helper, `reviewThenFix`, runs the
picked reviewers once, hands the findings to the fixer once, and returns —
no re-review. The fixer's "fixed" claims are trusted unverified at task
level.

Verification moves to a new final stage: after all tasks, the flow reviews
the **run's entire change set** (everything changed since the run started,
committed or not) with the existing multi-round review-and-fix loop, capped
at 5 rounds. This is where
cross-task and out-of-diff interactions get fresh-eyes review.

Adopted by the five task-based flows (`implement`, `implement-interactive`,
`implement-enhanced`, `issue-pr`, `issue-pr-bugfix`); flows that open a PR
run the final review before opening it. `simple.sc` (its one stage already
spans the whole branch) and `review.sc` (no fixer) are unchanged.

The diff base is the run's starting commit, recorded in the progress log at
setup. This works identically with and without `--skip-branch`, needs no
remote, and stays stable across resumes.

## PR 3 — Anti-deference reviewer prompts

Three prompt changes:

1. The instructions sent to every reviewer gain three rules: a deliberate
   or planned decision is evidence of intent, not of correctness — never a
   reason to withhold or soften a finding; findings whose consequence is
   user data loss, silent inversion of user intent, or a blocked/hung
   process must always be reported, at the severity the consequence
   deserves, even when the plan explicitly chose the behavior; the cost of
   the fix never lowers a finding's severity.
2. The fixer prompt requires stating which option was taken when a
   suggestion offered alternatives ("do X, or document why Y is safe"); the
   re-review prompt requires verifying that the chosen option resolves the
   original concern, not merely that it was performed.
3. The declined-findings block shown to reviewers states that a decline
   justified only by "the plan chose this" is insufficient for the
   mandatory-report categories above — such findings are to be re-reported.

## PR 4 — Reviewer-picker inclusion checklists

Each built-in reviewer's description gains a short, researched "include
when" checklist of concrete diff signals (e.g. performance: spawns
subprocesses, performs IO, adds work inside loops), so the picker matches
checklists against the changed files instead of judging scope relevance.
The picker must also state a brief rationale for the reviewers it excludes,
making selection auditable. The checklists themselves are the deliverable:
2–4 clauses per reviewer, each checkable against a diff.

## Out of scope (tracked separately)

A guaranteed selection floor (e.g. full roster in the final review's first
round); severity calibration; verifying fixer "fixed" claims at task level;
surfacing end-of-run open findings in the PR body.
