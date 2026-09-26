# Settings

Orca reads two `settings.properties` files. Both are plain `key = value` lines,
parsed once per [attempt](../glossary/users.md#flows-and-runs), before Orca
changes anything in the repository.

| File | Holds | Committed |
|---|---|---|
| `.orca/settings.properties` in the project | stack commands and, per role, which agent to use | yes, with the project |
| `~/.config/orca/settings.properties` (`$XDG_CONFIG_HOME/orca/`, also on macOS) | agent keys only | no, per user |

A missing global file is fine. A file that cannot be read or parsed, in either
place, aborts the run before Orca changes anything in the repository. A stack
key (`format`, `lint`, `test`) in the global file is also an error.

## Stack commands

Keys `format`, `lint` and `test`. Each is a **gate**: a command the review loop
can run over the change. Each value is one shell command, run with `bash -c`
in the flow's working directory. Everything after the first `=` is command
text, so `lint = FOO=bar cargo check` works.

- Repeating a key appends: the commands run in file order. A repository with
  two stacks lists one line per stack.
- The value `off` disables that gate explicitly. A missing key skips the gate
  too.
- `#` starts a comment. Commenting a line out is the same as deleting it.

A typical discovered file:

```properties
# orca settings — edit freely, commit with the project.
# format/lint/test: one shell command per key; `off` disables the gate. Delete the stack lines (or the whole file) to re-run auto-discovery.
# planningAgent/codingAgent/reviewAgent (harness[:model]): override the global settings file; a flow's own code overrides both.
# Cargo.toml; via rustfmt
format = cargo fmt
# Cargo.toml
lint = cargo check --tests
# no test config found
test = off
```

The [review loop](../authoring/review.md) runs `format` before each round and
`lint` with the reviewers. It never runs `test`, to stay cheap. A flow can read
all three as `summon[FlowContext].stackSettings` and run the tests in its own
stage, see [Gates and checks](../authoring/gates-and-checks.md).

## Agent keys

`planningAgent`, `codingAgent` and `reviewAgent`. Valid in both files, single
valued: a repeated agent key is an error. The value is `harness[:model]`, split
at the first `:`, so a model id containing `:` survives. `harness` is one of
`claude`, `codex`, `opencode`, `pi`, `gemini`; any other name is an error that
lists the valid ones.

```properties
planningAgent = claude:opus
codingAgent = codex:gpt-5-mini
reviewAgent = opencode:anthropic/claude-haiku-4-5
```

The model part is passed verbatim to the harness. Orca does not validate model
ids, with one exception: claude's bare `haiku` alias is sent as
`claude-haiku-4-5`, because the CLI may resolve the bare alias to a pricier
model.

Agent keys are read even when `flow(stackSettings = Some(...))` pins the stack
commands. Setup announces where each role came from:

```text
agents: planning=claude:claude-opus-5-5[1m] (default), coding=codex:gpt-5-mini (project), review=opencode:<harness default> (global)
```

`<harness default>` marks a role with no model pin: the harness picks one.
`[1m]` is claude's 1M-token context window variant.

Set the keys from the command line with `orca config --coding-agent codex`
or edit a file with `orca config --edit project|global`. See
[Orca Shell](shell.md).

## Precedence

Code always wins over files.

- **Roles:** `flow(planningAgent = ...)` (or `codingAgent` / `reviewAgent`) >
  project file > global file > built-in default (`claude`, no model pin).
- **Stack commands:** `reviewAndFixLoop(formatCommands = Use(...) / Off)` (see
  [Gates and checks](../authoring/gates-and-checks.md)) >
  `flow(stackSettings = Some(...))` > project file > auto-discovery, which
  writes the project file.

## Auto-discovery

Discovery runs only when the project file is absent or has no stack line at
all. A file with some stack keys is left alone. It spends one cheap, read-only
agent call inspecting the repository, then writes the file and announces every
guess:

```text
no .orca/settings.properties — discovering how to format, lint & test this project
  format = cargo fmt   # Cargo.toml; via rustfmt
  lint = cargo check --tests   # Cargo.toml
warning: stack settings: no test command — gate disabled
written to .orca/settings.properties — review and edit as needed.
```

Discovered lines are appended below existing content, so agent lines are never
touched. To run discovery again, delete the stack lines, delete the file, or
run `orca clear-stack`. With a complete file no model call is made; this is the
normal case, including in CI.

Each discovered command names the file it was inferred from. Two checks run
before the file is written: the executable must be on `PATH`, and the cited
file must exist. A command that fails either check is written as a comment,
such as `# skipped: lint = just check (just: not found on PATH)`, and never
run. A key left with no command gets a live `key = off` line. If discovery
itself fails, the run aborts rather than writing a "gates off" file.
