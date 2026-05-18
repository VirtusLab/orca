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
- A target project. Each example ships a `create-test-project.sh`
  next to its flow script that copies a small starter (in the
  example's `test-project/` directory) into a temp dir and inits
  git. The starters intentionally vary across examples — 01 is a
  Rust calculator crate, 02 a Java/Maven Calculator with a
  `.github/workflows/ci.yml`, 03 a Java todo-CLI with several
  obvious feature gaps. Edit the seed files there, not the
  script, if you want a different starter:

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
./examples/02-bugfix/create-test-project.sh --local /tmp/orca-demo
```

The flag runs `sbt publishLocal` in the orca checkout, reads the
dynver-derived version, and rewrites the copied flow script to pin
that version with `//> using repository ivy2Local`. Default
(without `--local`) is to resolve from Maven Central.

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
