# 0022. Review rework

Status: Accepted · Date: 2026-08-26
Related: [ADR 0011](0011-reviewer-roster.md) (reviewer roster, picker and
per-round narrowing; its 2026-08-02 amendment bounds the change set to the
enclosing stage — part 2 below is a deliberate exception),
[ADR 0018](0018-stage-bound-flow-runtime.md) (stage-bound runtime and its
progress log, which records a per-stage base commit — `stageBaseCommit`; part 2
adds a run-level record of the commit the run started at, and that, not the
per-stage one, is what the final review stage reads),
[ADR 0010](0010-prompts-and-helpers-convention.md) (where the prompt resources
parts 1 and 3 rewrite live).

## Context

A post-mortem of an `implement.sc` run found the review loop missing defects
that were plainly in the diff. Four causes, none of them the reviewers being
unable to see the problem:

- **Findings silently dropped.** The confidence gate held back anything under a
  per-severity bar. A reviewer that hedged a real defect had it filtered out
  before the fixer ever saw it.
- **Deference to the plan.** Reviewers treated a planned decision as settled and
  declined to report against it, including where the consequence was severe.
- **Cross-task interactions never reviewed.** Review is per task, over the
  change set that task's stage produced, so nothing ever looked at how the tasks
  combined.
- **Relevant reviewers never selected.** The picker had only prose descriptions
  to match against, so reviewers that should have run on a given diff did not.

## Decision

Four parts, one PR each. This is the one ADR for the whole rework; later PRs
amend it only where they deviate from what is written here.

### 1. Remove confidence (this PR)

Confidence scores and the per-severity gate are gone. In their place:

- A reviewer reports a finding **only when it believes it should be fixed**. No
  hedges, no unverified hunches, no style opinions. Fix cost is not a reason to
  withhold a verified finding.
- **Every reported finding reaches the fixer.** Nothing filters between the two.
- A decline carries a **reason**, is **shown to later rounds** — where a
  reviewer may re-report it with a rebuttal — and is **reported at exit** in the
  returned `IgnoredIssues`. Nothing is silently dropped.
- **Severity stays**, for ordering and labels only. It no longer gates anything.
- **Narrowing keys on what a reviewer actually reported**, so a reviewer retires
  only on genuine silence rather than on having had its findings filtered out.

Explicitly **no damping rule** for repeated declines. The round cap and the
zero-fixed halt already bound re-litigation; a damping rule would reintroduce
silent dropping to solve a cost problem we have not measured. Revisit if real
runs show the token waste.

No existing ADR specified the gate, so part 1 amends nothing.

### 2. Review shape: one pass per task, one verification pass per run

Per-task review becomes a **single pass** (`reviewThenFix`): reviewers once,
fixer once, no re-review. A "fixed" claim is trusted at task level.

Verification moves to a **final whole-branch stage** after all tasks, running
the multi-round review-and-fix loop over everything changed since the run
started — base is the commit the run started at, recorded in the progress log at
setup as a run-level baseline, not a stage's own `stageBaseCommit`. Capped at 5
rounds. The five task-based flows adopt it, before PR-opening where
applicable; `simple.sc` and `review.sc` are unchanged.

> **Amendment (2026-08-26, on implementing part 2).** The recorded baseline is
> not always usable: a log written before orca recorded one, a hand-edited
> value, or a commit that no longer sits behind HEAD after a rebase or in a
> fresh clone. Diffing against any of those reviews the wrong range, so the
> final stage then **skips**: it emits a step naming why and returns no
> findings, leaving the run otherwise untouched.

This is a **deliberate exception** to ADR 0011's argument (amendment 2026-08-02,
[adr/0011:217](0011-reviewer-roster.md)) that "it is the stage, not the branch,
that bounds the change set". That argument holds for per-task review, and stands
— pulling every earlier task into one task's review is exactly the waste it
rules out. The final stage exists precisely to see what the per-stage bound
excludes: cross-task interactions, and the effect of a task's change on code
outside its own diff.

### 3. Anti-deference prompt rules

Reviewer prompts gain:

- A deliberate or planned decision is **evidence of intent, not of
  correctness**.
- Findings whose consequence is **user data loss, silent inversion of user
  intent, or a blocked/hung process** must **always** be reported, at the
  severity the consequence deserves, even where the plan chose that behaviour.
- **Fix cost never lowers severity.**
- The fixer must **state which alternative it took**, and re-review verifies the
  chosen option resolves the concern.
- For the mandatory-report categories above, a decline justified only by "the
  plan chose this" is **insufficient**.

### 4. Reviewer-picker inclusion checklists

Each built-in reviewer's description gains 2–4 concrete **"include when"**
clauses keyed on diff signals, so the picker matches checklists against the
changed files rather than interpreting prose. The picker states a brief
rationale for each exclusion.

## Tracked separately

Out of scope here, deliberately: a selection floor (a minimum set of reviewers
that always run), severity calibration, verifying task-level "fixed" claims, and
surfacing still-open findings in the PR body.
