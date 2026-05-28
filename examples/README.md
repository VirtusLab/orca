# Examples

Four end-to-end Orca flows, each a single `.sc` script you can run
with `scala-cli`. Pick by what you're trying to do:

| Example | When to use it |
| ------- | -------------- |
| [01-simple](01-simple/) | One-shot planning + coding for small tasks. Autonomous planner. The plan is in memory; no resume, no on-disk state. |
| [02-interactive](02-interactive/) | Same shape as 01, but the planner can ask clarifying questions via the `ask_user` MCP tool. Use when the prompt is open-ended. |
| [03-bugfix](03-bugfix/) | Issue-driven Scala bugfix. Triage (real bug? testable?) → failing test on a fresh branch → PR → CI confirms red → sonnet posts a focused failure comment → fix → push. Touches GitHub. |
| [04-epic](04-epic/) | Multi-task workstream ("epic") in a resumable markdown file (`epic.md`). Each task is reviewed in parallel by Claude *and* Codex before being marked complete. Re-runs pick up at the first `[ ]` task. Ends with a documentation update and an epic-file cleanup. |

## Common prerequisites

All four examples expect:

- **JDK 21+** and [scala-cli](https://scala-cli.virtuslab.org/).
- `claude` CLI logged in (`claude auth login` — see the
  [repo root README](../README.md#authenticating-the-coding-agents)).
- A target project. Each example ships a `create-test-project.sh`
  next to its flow script that copies a small starter (in the
  example's `test-project/` directory) into a temp dir and inits
  git. The starters intentionally vary across examples — 01 and 02
  share a small Rust calculator crate (concrete vs ambiguous prompt),
  03 a sbt-based Scala Calculator with a `.github/workflows/ci.yml`,
  04 a Java todo-CLI with several obvious feature gaps. Edit the seed
  files there, not the script, if you want a different starter:

  ```bash
  ./examples/01-simple/create-test-project.sh
  # or pass an explicit destination:
  ./examples/01-simple/create-test-project.sh /tmp/orca-demo
  ```

  Each script prints the next-step command (a `scala-cli run` of
  the example's `.sc` file) when it's done.

### Running against a local Orca build (`--local`)

If you're hacking on Orca itself and want the example to pick up
your in-tree changes rather than the published Maven Central
artifact, pass `--local` to any of the seed scripts:

```bash
./examples/01-simple/create-test-project.sh --local
./examples/03-bugfix/create-test-project.sh --local /tmp/orca-demo
```

The flag runs `sbt publishLocal` in the orca checkout, reads the
dynver-derived version, and rewrites the copied flow script to pin
that version with `//> using repository ivy2Local`. Default
(without `--local`) is to resolve from Maven Central.

Example 03 additionally needs:

- `gh` (GitHub CLI) authenticated against the target repo.
- A CI workflow that runs the test suite on push (the seed ships an
  `sbt test` workflow).
- A GitHub issue on the target repo describing the bug — the flow
  takes a `<owner>/<repo>#<number>` reference as its argument.

Example 04 additionally needs:

- `codex` CLI logged in alongside `claude` — the after-task
  reviewers run on both backends in parallel.

## Reading the output

The repo root README has a [glyph legend](../README.md#how-it-works)
for the rendered output. The full design rationale (event log
above, status bar below, `▶`/`▸`/`●`/`⏺`/`⎿`/`✖`/`?` mapping) lives
in [ADR 0008](../adr/0008-terminal-output-design.md).
