# Examples

Three end-to-end Orca flows, each a single `.sc` script you can run
with `scala-cli`. Pick by what you're trying to do:

| Example | When to use it |
| ------- | -------------- |
| [01-simple](01-simple/) | One-shot planning + coding for small tasks. The plan is in memory; no resume, no on-disk state. |
| [02-bugfix](02-bugfix/) | Bug report → failing test (or `REPRODUCTION.md`) → PR → CI confirms red → fix → CI green. Touches GitHub. |
| [03-extended-planning](03-extended-planning/) | Markdown-backed plan in `dev.md`. Resumable: a re-run picks up at the first `[ ]` task. Ends with a documentation update and a plan-file cleanup. |

## Common prerequisites

All three examples expect:

- **JDK 21+** and [scala-cli](https://scala-cli.virtuslab.org/).
- `claude` CLI logged in (`claude auth login` — see the
  [repo root README](../README.md#authenticating-the-coding-agents)).
- Orca published locally: `cd <orca-sandbox> && sbt publishLocal`.
- A target project. The seed script
  [`create-test-project.sh`](create-test-project.sh) creates a tiny
  Java calculator project useful for smoke-testing examples 01 and 03:

  ```bash
  ./examples/create-test-project.sh /tmp/orca-demo
  cd /tmp/orca-demo
  ```

Example 02 additionally needs:

- `gh` (GitHub CLI) authenticated against the target repo.
- A CI workflow that runs the test suite on push.

## Reading the output

The repo root README has a [glyph legend](../README.md#how-it-works)
for the rendered output. The full design rationale (event log
above, status bar below, `▶`/`▸`/`●`/`⏺`/`⎿`/`✖`/`?` mapping) lives
in [ADR 0008](../adr/0008-terminal-output-design.md).
