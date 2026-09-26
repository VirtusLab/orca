# 0024. Formatting runs inside the review loop, committed with the stage

Status: Accepted · Date: 2026-09-26
Related: [ADR 0019](0019-project-stack-settings.md) (where the `format`
commands come from; this ADR says where they run), [ADR 0018](0018-stage-bound-flow-runtime.md)
(stages commit with `add -A`; `WorkspaceWrite` is fork-opaque).

## Context

The project's `format` commands (ADR 0019) rewrite sources in place. Two
things must hold whenever they run:

- Reviewers, the lint gate and checks see formatted code, so they spend no
  turns on whitespace. This is why a format step was added at all
  (`0e775ac2`, 2026-05-02: a standalone `stage("Format")` before review).
- The tree a stage commits is formatted, including the fixer's edits. The
  standalone stage broke this: fix turns edit after it ran, so review-fix
  edits were committed unformatted. PR #9 (2026-06-08) moved formatting into
  the loop for that reason.

Neither the original placement nor the move was recorded as a decision.
This ADR records the placement and the alternatives weighed on 2026-09-26.

## Decision

Formatting is a step of the review loop, not a stage and not a commit of its
own:

- `formatWorkspace()` in `ReviewLoop.scala` runs the commands at the start of
  every review round, and after a fix turn that no round follows.
- It takes `WorkspaceWrite`, so it cannot be moved into the reviewer fan-out:
  the token is fork-opaque, and lint runs concurrently with the reviewers and
  must never rewrite sources.
- A nonzero exit is reported as a `Step` and stops nothing.
- Whatever the formatter rewrote is swept into the enclosing stage's commit
  by the stage's `add -A`. There is no separate format commit.

## Alternatives considered

- **Format once after coding, before review, as its own stage.** The pre-PR-#9
  placement. Fix turns edit after it, so the committed tree ends
  unformatted. Rejected for the reason PR #9 gives.
- **Format after every durable coder turn** (a hook in `FlowSession.run`,
  which already takes `WorkspaceWrite`). Meets both requirements, covers
  coder stages that contain no review loop, and lets the loop forget about
  formatting. Not adopted now: the loop placement meets both requirements,
  and no run has shown the uncovered stages to matter. Preferred shape if the
  placement is ever reworked.
- **A run-start baseline format, committed on its own** like the discovered
  settings commit of ADR 0019. Keeps a tree-wide sweep out of task commits,
  blame and per-task reviewer diffs. Not adopted now: it only matters for a
  target repo whose configured formatter was never run over the tree, and
  the frequency of that is unmeasured. Compatible with the current
  placement if it is added later.
- **A format commit per task or at run end.** Buys only a tidier log. Per
  task it leaves reviewers seeing unformatted code unless the loop formats
  anyway; at run end it leaves every task commit unformatted. Rejected.
- **Telling the coder to format.** Unreliable, costs coder tokens, and the
  README's stance is that formatting is the script's job, not the agent's.
  Rejected.
- **Change-set-scoped formatting** (a `{files}` placeholder in the command).
  Needs a settings-grammar change. Unnecessary if a baseline format commit is
  ever added. Not adopted.

## Consequences

- Formatting churn lands in the same commit as the semantic change of that
  stage. Orca pushes stage commits as-is, so a commit-by-commit PR review
  sees the churn where it happened.
- A tree-wide formatter on a never-formatted target repo reformats the whole
  tree into the first stage that reviews, and the reviewers of that round
  see the sweep in their diff. The reviewer selector does not: it is
  prepared before the first round formats.
- Coder stages with no review loop (the failing-test stage of
  `issue-pr-bugfix.sc`, the documentation stage of `implement-enhanced.sc`)
  commit unformatted. The next loop formats their edits into its own commit.
- The loop formats after a fix turn and again at the next round start with no
  edits in between. Harmless, and the test pinning "before every round" is
  the contract.
