# Example 03 — running an epic with cross-agent review

A more involved flow than [01-simple](../01-simple/): the epic
lives on disk in a file the human can read, edit before running,
and inspect mid-flow; and after each task lands, both Claude and
Codex review the result in parallel. Crashes don't lose
progress — each task's `[x]` checkbox is committed before the
next one starts, and a re-run picks up where the previous run
stopped.

We use "epic" in the agile sense: a multi-task workstream big
enough that you want it written down before starting, broken
into smaller reviewable tasks, and resumable across sessions.

## When to reach for this

- The work spans more than a single LLM session: the user wants
  visibility into what's planned before hitting "go".
- The agent might fail partway through (long sequences, flaky
  tools, cost ceilings). Resume should be free.
- One model's blind spots shouldn't decide whether a task ships.
  Cross-backend review is cheap insurance against a single
  vendor's failure modes.
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

The schema description is exposed as
`orca.plan.extended.Plan.SchemaDescription` so flows that want to
splice it into their own custom planner prompt can.

## Stages

1. **Acquiring epic** — `Plan.loadOrGenerate(file, prompt, llm)`:
   - File exists → parse and reuse (logs a Step that the file is
     being reused, including how many tasks are already complete).
   - File missing → ask the LLM to produce the epic in the schema,
     write it to disk, return.
2. **Ensure clean working tree** — `git.ensureClean(...)`. Stashes
   any pending changes so the flow doesn't tear them up; recovery
   is `git stash pop`.
3. **Checkout branch** — `git.checkoutOrCreate(plan.branchName)`.
   No-op if we're already on the branch (resume case).
4. **For each incomplete task**:
   - `claude.continueSession(sessionId, task.prompt)`.
   - `git.commit("task: <name>")`.
   - `reviewAndFixLoop(...)` with reviewers
     `defaultReviewers(claude) ++ defaultReviewers(codex)` —
     both backends run all five canonical reviewer dimensions
     (performance, readability, test coverage, code
     functionality, abstraction) in parallel; fixes go back
     through the original Claude session.
   - `Plan.persistComplete(file, task.name)` — flips the checkbox
     in `epic.md` so a future run skips this task.
5. **Update documentation** — agent updates README / doc-comments
   to reflect the changes, and commits.
6. **Remove epic file** — `os.remove(epicMd)`, committed as the
   final cleanup.

## Why two backends?

A single backend's reviewer is a strong filter, not a perfect
one. The same model that wrote the code is unlikely to flag the
class of mistakes its training distribution makes. Running the
review prompt against a second backend (`codex`) on the same
diff is a cheap way to widen coverage: when the two backends
disagree, that's the interesting case worth surfacing.

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
- `com.virtuslab::orca:0.1.0-SNAPSHOT` published locally (`sbt publishLocal`).
- A target project. The sibling
  [`create-test-project.sh`](create-test-project.sh) copies a
  Calculator starter from [`test-project/`](test-project/) into
  a temp dir and inits git.

## Run

```bash
cd <project>
scala-cli run <orca-sandbox>/examples/03-epic/epic.sc -- \
  "Add a divide method to Calculator with full test coverage"
```

## Watching it work

The renderer's status bar shows the current stage. The event log
shows each task's start, the agent's tool calls (file reads, edits,
tests), the parallel reviewer turns from both backends, and any
Step events from `git` and the loop machinery (branch switches,
epic-file reuse, etc.). When something fails, the epic file's
checkboxes are the truth — anything still `[ ]` will run on the
next attempt.
