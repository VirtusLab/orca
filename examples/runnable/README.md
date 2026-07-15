# Runnable examples

Two end-to-end Orca flows you can seed and run with one command. Each ships a
`create-test-project.sh` that copies a small starter (in the example's
`test-project/`) into a temp dir, inits git, and copies the flow script — one of
the `.sc` files in [`examples/`](../) — alongside it.

| Example | When to use it |
| ------- | -------------- |
| [01-simple](01-simple/) | One-shot planning + coding for small tasks. Autonomous planner; the plan is in memory — no resume, no on-disk state. |
| [02-interactive](02-interactive/) | Same shape as 01, but the planner can ask clarifying questions via the `ask_user` MCP tool. Use when the prompt is open-ended. |

The other flow scripts in [`examples/`](../) (`epic.sc`, `issue-pr.sc`,
`issue-pr-bugfix.sc`, `implement-enhanced.sc`) have no seed harness — run them
against your own git repo.

## Prerequisites

Both examples expect:

- **JDK 21+** and [scala-cli](https://scala-cli.virtuslab.org/).
- `claude` CLI logged in (`claude auth login` — see the
  [repo root README](../../README.md#authenticating-the-coding-agents)).
- `cargo` on PATH — both seed a small Rust calculator crate.

Seed a project:

```bash
./examples/runnable/01-simple/create-test-project.sh
# or pass an explicit destination:
./examples/runnable/01-simple/create-test-project.sh /tmp/orca-demo
```

Each script prints the next-step command (a `scala-cli run` of the example's
`.sc` file) when it's done. Edit the example's `test-project/` for a different
starter.

### Seed and run in one step (`--run`)

Pass `--run` to seed the project and then immediately `cd` into it and execute
the printed `scala-cli run ...` with the example's suggested prompt:

```bash
./examples/runnable/01-simple/create-test-project.sh --run
```

### Seeding stack settings (`--settings`)

Pass `--settings` to include a ready `.orca/settings.properties` (the starter's
`format`/`lint`/`test` commands) in the seed commit:

```bash
./examples/runnable/01-simple/create-test-project.sh --settings
```

The flow then skips the stack auto-discovery model call — useful for offline or
deterministic runs. Delete the file in the seeded project to get auto-discovery
back.

### Running against a local Orca build (`--local`)

If you're hacking on Orca itself and want the example to pick up your in-tree
changes rather than the published Maven Central artifact, pass `--local`:

```bash
./examples/runnable/01-simple/create-test-project.sh --local
```

It runs `sbt publishLocal` in the orca checkout, reads the dynver-derived
version, and rewrites the copied flow script to pin that version with
`//> using repository ivy2Local`. Without `--local`, the flow resolves from
Maven Central.

## Reading the output

The repo root README has a [glyph legend](../../README.md#how-it-works) for the
rendered output. The full design rationale lives in
[ADR 0008](../../adr/0008-terminal-output-design.md).
