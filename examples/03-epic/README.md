# Example 03 — running an epic with cross-agent review

A more involved flow than [01-simple](../01-simple/): the epic
lives on disk in a file the human can read, edit before running,
and inspect mid-flow; and after each task lands, the *other*
backend reviews the result. Claude implements; Codex reviews.
Crashes don't lose progress — each task's `[x]` checkbox is
committed before the next one starts, and a re-run picks up
where the previous run stopped.

We use "epic" in the agile sense: a multi-task workstream big
enough that you want it written down before starting, broken
into smaller reviewable tasks, and resumable across sessions.

## When to reach for this

- The work spans more than a single LLM session: the user wants
  visibility into what's planned before hitting "go".
- The agent might fail partway through (long sequences, flaky
  tools, cost ceilings). Resume should be free.
- The implementing agent shouldn't be its own reviewer. Routing
  reviews through a different backend widens coverage with
  little extra cost.
- A reviewer wants to read the epic as a markdown document, not a
  JSON blob.

## On-disk format

The epic file (default `epic.md`, at the working-directory root)
follows a strict schema the library both writes and parses:

```markdown
# Plan: <branchName>

## Task: <short-name>
Status: [ ]

<prompt body — free-form, can span paragraphs>

## Task: <short-name>
Status: [x]

<prompt body>
```

- `# Plan:` line carries the branch name.
- `## Task:` blocks have a short kebab-case name, a `Status: [ ]` /
  `Status: [x]` checkbox, and a free-form prompt body.
- `[ ]` is pending, `[x]` is complete.

## Stages

1. **Acquiring epic** — `Plan.autonomous.loadOrGenerate(file, prompt, llm)`:
   - File exists → parse and reuse (logs a Step that the file is
     being reused, including how many tasks are already complete).
   - File missing → ask the LLM to produce a `Plan` (structured),
     render it to markdown, write to disk, return.
   - Swap to `Plan.interactive.loadOrGenerate` if you want the
     planner to run as a conversation rather than a single turn.
2. **Ensure clean working tree** — `git.ensureClean(...)`. Stashes
   any pending changes so the flow doesn't tear them up; recovery
   is `git stash pop`.
3. **Checkout branch** — `git.checkoutOrCreate(plan.branchName)`.
   No-op if we're already on the branch (resume case).
4. **For each incomplete task**:
   - `claude.autonomous.continueSession(sessionId, task.prompt)`.
   - `git.commit("task: <name>")`.
   - `reviewAndFixLoop(...)` with `reviewers =
     allReviewers(codex)` — seven reviewer dimensions
     (performance, readability, test, code-functionality,
     abstraction, backend-architect, scala-fp); an LLM-driven
     selector picks the relevant ones for each task and runs
     them on Codex in parallel; fixes go back through the
     original Claude session.
   - `Plan.persistComplete(file, task.name)` — flips the checkbox
     in `epic.md` so a future run skips this task.
5. **Update documentation** — agent updates README / doc-comments
   to reflect the changes, and commits.
6. **Remove epic file** — `os.remove(epicMd)`, committed as the
   final cleanup.

## Why split implementer and reviewer?

A single backend's reviewer is a strong filter, not a perfect
one. The same model that wrote the code is unlikely to flag the
class of mistakes its training distribution makes. Running the
review against a *different* backend (Codex while Claude
implements, here) is a cheap way to widen coverage — and shifts
the failure mode away from "Claude rubber-stamps Claude".
Swap which side is which by editing the script's
`val reviewers = allReviewers(codex)` line.

## Resume semantics

If the script crashes mid-flow, just rerun it. The runtime:

- Reuses the existing `epic.md`.
- Stashes any leftover working-tree changes.
- Switches back to the epic's branch.
- Skips tasks already marked `[x]`.
- Picks up from the first `[ ]` task.

If the user pre-writes `epic.md` themselves, the planner stage
becomes "we trust your file" — useful for handcrafted epics.

## Prerequisites

- JDK 21+, scala-cli.
- `claude` and `codex` CLIs both logged in (see the repo root
  README for auth setup).
- A target project. The sibling
  [`create-test-project.sh`](create-test-project.sh) copies a
  tiny todo-CLI starter from [`test-project/`](test-project/)
  into a temp dir, drops the flow script —
  [`plans/epic.sc`](../../plans/epic.sc) — alongside it, and inits
  git. The starter is deliberately feature-incomplete (no
  persistence, no `done`/`delete`, no priorities) so the example
  prompt below decomposes into several tasks rather than one.

## Run

```bash
cd /tmp/orca-03-epic-…   # the seed-script's temp dir
scala-cli run epic.sc -- \
  "Persist tasks to a JSON file at ~/.todo/tasks.json (load on startup, save on every change), \
   add 'done <id>' and 'delete <id>' commands, and support priority levels (low/medium/high) \
   with a 'list --priority' filter"
```

The agent should plan something like four tasks (persistence,
the two new commands, the priority field + filter) before it
starts implementing.

## Watching it work

The renderer's status bar shows the current stage. The event log
shows each task's start, the agent's tool calls (file reads, edits,
tests), the parallel reviewer turns from Codex, and any
Step events from `git` and the loop machinery (branch switches,
epic-file reuse, etc.). When something fails, the epic file's
checkboxes are the truth — anything still `[ ]` will run on the
next attempt.
