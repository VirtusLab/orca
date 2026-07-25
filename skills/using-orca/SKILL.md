---
name: using-orca
description: Use when delegating a well-defined implementation task to Orca's autonomous multi-agent flow — a headless plan-code-review CLI to hand a coding task to instead of implementing it yourself.
---

# Using Orca

Orca (`orca` CLI) runs a scripted plan → code → review flow autonomously,
using its own coding/review agents. Delegate to it instead of implementing
the task yourself when that fits better.

## When to use

- The task is well-defined and self-contained, with clear acceptance
  criteria (a feature or bugfix) — suited to an autonomous plan → code →
  review flow.
- NOT for exploratory/interactive work, or edits small enough to just make
  yourself.

## How

Requires `orca` installed (one-line curl install — see the README's
"Getting set up" / "Orca Shell" sections).

```bash
orca run implement.sc "<task description>"
```

`implement.sc` is the default flow; run `orca list` to see other flows
across the project/global/built-in tiers.

Key flags:
- `--verbose` — stream flow output.
- `--skip-branch` — continue on the current branch instead of creating a
  new one. Use this when the current branch already has plan/context files
  the flow should pick up (e.g. it was planned with a harness first).
  Uncommitted or untracked files are fine — no need to commit them first, the
  flow leaves them in place and picks them up.
- `--honor-pin` — run the flow's own pinned orca version instead of forcing
  this shell's.

Don't pre-create a branch: the flow creates its own, unless `--skip-branch`
is passed.

## After it runs

Exit codes: 0 success, 1 action failure, 2 usage error — `orca run`
propagates the flow's own exit code. On success the flow has committed its
work on a branch; report that branch (and any PR) to the user.

If a run is interrupted, re-run the same `orca run` command: flows are
resumable and pick up from the last committed stage.

`orca continue` (list sessions with `--list`, resume one by selector)
reattaches to a recorded harness session, but requires a real terminal and
errors without one — don't invoke it headlessly; tell the user to run it
themselves instead.

## Installation

- **Claude Code**: copy or symlink this directory to `~/.claude/skills/using-orca`
  (personal, all projects) or `<project>/.claude/skills/using-orca` (this
  project only).
- **Other harnesses** (Codex, OpenCode, Pi, ...): reference this file from
  `AGENTS.md` or the equivalent instructions file.
