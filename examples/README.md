# Examples

Three end-to-end Orca flows, each a single `.sc` script you can run
with `scala-cli`. Pick by what you're trying to do:

| Example | When to use it |
| ------- | -------------- |
| [01-simple](01-simple/) | One-shot planning + coding for small tasks. The plan is in memory; no resume, no on-disk state. |
| [02-bugfix](02-bugfix/) | Bug report → failing test (or `REPRODUCTION.md`) → PR → CI confirms red → fix → CI green. Touches GitHub. |
| [03-epic](03-epic/) | Multi-task workstream ("epic") in a resumable markdown file (`epic.md`). Each task is reviewed in parallel by Claude *and* Codex before being marked complete. Re-runs pick up at the first `[ ]` task. Ends with a documentation update and an epic-file cleanup. |

## Common prerequisites

All three examples expect:

- **JDK 21+** and [scala-cli](https://scala-cli.virtuslab.org/).
- `claude` CLI logged in (`claude auth login` — see the
  [repo root README](../README.md#authenticating-the-coding-agents)).
- Orca published locally: `cd <orca-sandbox> && sbt publishLocal`.
- A target project. Each example ships a `create-test-project.sh`
  next to its flow script that copies a tiny Java calculator
  starter (in the example's `test-project/` directory) into a
  temp dir and inits git. Edit the seed files there, not the
  script, if you want a different starter:

  ```bash
  ./examples/01-simple/create-test-project.sh
  # or pass an explicit destination:
  ./examples/01-simple/create-test-project.sh /tmp/orca-demo
  ```

  Each script prints the next-step command (a `scala-cli run` of
  the example's `.sc` file) when it's done.

Example 02 additionally needs:

- `gh` (GitHub CLI) authenticated against the target repo.
- A CI workflow that runs the test suite on push.

Example 03 additionally needs:

- `codex` CLI logged in alongside `claude` — the after-task
  reviewers run on both backends in parallel.

## Reading the output

The repo root README has a [glyph legend](../README.md#how-it-works)
for the rendered output. The full design rationale (event log
above, status bar below, `▶`/`▸`/`●`/`⏺`/`⎿`/`✖`/`?` mapping) lives
in [ADR 0008](../adr/0008-terminal-output-design.md).
