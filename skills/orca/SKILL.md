---
name: orca
description: Use when the user asks to run an Orca flow (e.g. "use orca to implement …"), or when delegating a well-defined implementation task to Orca's autonomous multi-agent flow — a headless plan-code-review CLI to hand a coding task to instead of implementing it yourself.
argument-hint: "[task]"
---

# Orca

Orca (`orca` CLI) runs a scripted plan → code → review flow autonomously,
using its own coding/review agents. Delegate to it instead of implementing
the task yourself when that fits better.

## When to use

- The user asks for it (`/orca`, "use an orca flow to …").
- The task is well-defined and self-contained, with clear acceptance
  criteria (a feature or bugfix) — suited to an autonomous plan → code →
  review flow.
- NOT for exploratory/interactive work, or edits small enough to just make
  yourself.

## 1. Choose the flow and where the run goes

Run `orca list --json` and `git status --porcelain`. If `orca` is not found,
stop and point the user to https://github.com/VirtusLab/orca#getting-set-up.

Skip a question only if the user named the flow, or said where the run goes,
explicitly. Otherwise ask both in one AskUserQuestion call:

**Flow** (header `Flow`). Put every flow in the question text, one per line
as `name — description`, and say: pick one of the options, or type any other
flow name under "Other". Options: `implement.sc` first, marked
"(Recommended)", then up to 3 more, by `origin`: `project`, `global`,
`built-in`.

**Where the run goes** (header `Where`). On a clean tree, the options are:

- New branch (Recommended) — no flag.
- Current branch — the flow commits onto it: `--skip-branch`.
- New worktree — a separate checkout under `.orca/worktrees/`, this one
  stays untouched: `--worktree`.

With local (uncommitted or untracked) changes, list up to 10 changed files in
the question text (then "+N more"), and offer:

- New branch, keep local changes (Recommended) — for local files the flow
  should use, e.g. a spec: `--keep-changes`.
- New branch, stash local changes — the run starts from the last commit: no
  flag.
- Current branch, keep local changes: `--skip-branch`.
- New worktree — local changes are NOT included, it starts from the last
  commit: `--worktree`.

Without `--keep-changes` or `--skip-branch`, a headless run stashes local
changes. With either, if the flow fails before its first commit, kept changes
to tracked files are lost.

If the user picks the worktree while there are local changes, ask with
AskUserQuestion (header `Changes`): "Commit first" — so the worktree has
them — or "Run without them". To commit:
`git add -A && git commit -m "<short summary>"`.

Don't pre-create a branch or worktree: the flow creates its own.

## 2. Get the task

Task given with the invocation (may be empty):

$ARGUMENTS

If empty, use the task from the conversation. If there is none, ask for it in
plain text, phrased after the chosen flow's description (e.g. an issue
reference, a PR or branch to review, or a prompt describing the change).

Orca's agents don't see this conversation: write the task so it stands on its
own — goal, acceptance criteria, relevant files, and the path of any spec.

## 3. Run

Capture the output in a temporary file, so the user can follow it
(`tail -f`). Runs are long, so use two separate commands:

1. Create the file, and give the user its path before starting the run:

   ```bash
   mktemp -t orca-run.XXXXXX.log
   ```

2. Start the run with Bash `run_in_background: true`, using the literal path
   from step 1 and the flags chosen above. The task goes on stdin, so quotes
   and `$` in it need no escaping:

   ```bash
   orca run <flow> <flags> > <path> 2>&1 <<'ORCA_TASK'
   <task>
   ORCA_TASK
   echo "orca exit code: $?" >> <path>
   ```

When it finishes, read the file's tail to check the result; repeat its path
in your final report.

Add these flags only if the user asks:
- `--verbose` — print a stack trace if the flow aborts.
- `--honor-pin` — run the flow's own pinned orca version instead of forcing
  this shell's.
- `--branch <name>` — create the run's branch under this name. Refused with
  `--skip-branch`, and when the branch already exists.

## After it runs

Exit codes: 0 success, 1 action failure, 2 usage error — `orca run`
propagates the flow's own exit code. On success the flow has committed its
work on a branch; report that branch (and any PR) to the user, and the
worktree path for a `--worktree` run. The code flows end by opening a PR when
the repository is on GitHub. A new-branch run then leaves you on the branch
it started from — the work is on the feature branch behind the PR, not the
one you are standing on.

Whenever the output has an "Open review findings" block, show it to the user
verbatim. Say the review left nothing open only when a code flow exited 0
without one: a failed run may have stopped before printing it.

If a run is interrupted, re-run the same `orca run` command: flows are
resumable and pick up from the last committed stage. A re-run stashes local
changes, even with `--keep-changes`.

`orca continue` (list sessions with `--list`, resume one by selector —
an index, session name, or branch, e.g. `orca continue <branch>`)
reattaches to a recorded harness session, but requires a real terminal and
errors without one — don't invoke it headlessly; tell the user to run it
themselves instead.
