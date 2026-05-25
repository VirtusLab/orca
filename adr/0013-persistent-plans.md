## ADR 0013: Persistent plans as the default for multi-task flows

## Context

The library originally split planning into two shapes:

- **In-memory** — `Plan.autonomous.from` / `Plan.interactive.from` return a
  `(SessionId, Plan)`. The flow iterates `tasks` and forgets the plan when it
  exits. A crash mid-loop loses everything since the last commit.
- **Markdown-backed** — `Plan.autonomous.loadOrGenerate` /
  `Plan.interactive.loadOrGenerate` round-trip the plan through a `## Task: …`
  markdown file; `Plan.persistComplete` ticks the checkbox after each task
  lands. Only `epic.sc` actually used it.

In practice, every multi-task flow benefits from the persistent shape: an
agent run that takes hours shouldn't lose half its work to a one-off network
hiccup. The in-memory variant survived because there was no convention for
where to put the file or how to recover from one.

## Decision

Persistent is the default for multi-task flows. Three pieces fall out of that:

1. **Default file path: `.orca/plan-<hash>.md`** under `workDir`, where
   `<hash>` is the first 6 bytes of SHA-256(userPrompt), rendered as 12 hex
   chars. Two unrelated prompts in the same repo don't collide; rerunning
   the same prompt resumes the same plan. `.orca/` is hidden so it doesn't
   clutter `ls`. Exposed as `Plan.defaultPath(userPrompt)`.

2. **`Plan.recover(file): Option[Plan]`** — the resume-from-crash entry
   point. If the file exists: `git.ensureClean` (stash any pending edits;
   the user can `git stash pop` after the run), `git.checkoutOrCreate(plan.epicId)`,
   parse and return. If not: `None`, and the caller falls back to generating.
   The stash-before-parse order matters: an in-flight crash may have left
   edits to the plan file itself.

3. **`Plan.runPersistent(file, plan)(body: Task => Unit)`** — the iteration
   helper. Owns the loop (re-reads the plan after each task so persisted
   completions shape resume), the per-task `persistComplete` + `git.commit
   ("task: <title>")`, and the final cleanup commit (`os.remove` +
   `chore: remove <file.name>`). The body is a closure so flows can supply
   their own reviewer set, lint command, format step, etc.

The on-disk plan is committed alongside the work it tracks: each task commit
includes both the implementation and the ticked checkbox. So `git log`
shows exactly which tasks have shipped without needing the plan file.

## Why this scope split

`runPersistent` deliberately owns only iteration + persistence + cleanup, not
the implementation-and-review loop. Per-task bodies vary too much across
flows (different reviewer rosters, different lint commands, different format
steps) to bake into a single helper. Recovery, in contrast, *is* uniform —
every persistent flow needs the same stash + checkout + parse dance.

## Why stash (not fail) on a dirty tree

`recover` stashes pending edits rather than failing. The resume path runs
after a crash, where the user typically *wants* the resume more than their
stale local edits — and `git stash pop` recovers anything important. Failing
loudly would block the common case to protect the rare one.

## Why no tag on completion

After all tasks are done, the cleanup commit removes the plan file and
that's the end-of-epic marker. We considered also tagging the commit, but
one tag per epic clutters the tag namespace; the branch + epic-id is enough.

## Migration

- `implement.sc`, `implement-interactive.sc`, `epic.sc` switched to
  `Plan.defaultPath(userPrompt)` + `Plan.recover(...).getOrElse(generate)`
  + `Plan.runPersistent(planFile, plan)`.
- `issue-pr.sc` stays in-memory: the issue body + agent assessment drive
  the plan; the plan isn't useful to preserve once the PR is open. The
  branch + closing-comment carry the state.
- `bugfix.sc` stays as-is: it has its own state machine (triage → red test
  → PR → CI → fix → CI green) that doesn't map onto per-task iteration.

## When to opt out

- One-shot scripts that don't iterate over a task list.
- Bug-driven flows with their own state machine (see `bugfix.sc`).
- Workflows where persistence would actively confuse the user (e.g. the
  same prompt is meant to be re-run from scratch each time — vanishingly
  rare in practice).
