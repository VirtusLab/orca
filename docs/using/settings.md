# Settings

Orca reads its configuration from two `settings.properties` files. Both are
plain `key = value` files, parsed once per
[attempt](../glossary/users.md#flows-and-runs), before Orca changes anything in
the repository:

- `.orca/settings.properties` in the project holds the stack commands and, per
  role, which agent to use. It is committed with the project.
- `~/.config/orca/settings.properties` (or `$XDG_CONFIG_HOME/orca/`; the same
  location is used on macOS) holds agent keys only. It is per user and not
  committed.

A missing global file is fine. However, a file that exists but cannot be read
or parsed, in either place, aborts the run before Orca changes anything in the
repository. Note that a stack key (`format`, `lint`, `test`) in the global file
is also an error: stack commands belong to the project.

## Stack commands

The keys `format`, `lint` and `test` describe the project's stack. Each of them
is a **gate**: a command the review loop can run over the change. The value is
a single shell command, which Orca runs with `bash -c` in the flow's working
directory. Everything after the first `=` is taken as command text, so a line
such as `lint = FOO=bar cargo check` works as you would expect.

A few rules apply to these keys:

- Repeating a key appends another command; the commands run in file order.
  This is useful when a repository has two stacks: list one line per stack.
- The value `off` disables the gate explicitly. A missing key skips the gate as
  well.
- `#` starts a comment. Commenting a line out is the same as deleting it.

For example, this is what a typical file written by
[auto-discovery](#auto-discovery) looks like:

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
`lint` together with the reviewers. It never runs `test`, so that the loop
stays cheap. A flow can read all three commands as
`summon[FlowContext].stackSettings` and run the tests in a stage of its own;
see [Gates and checks](../authoring/gates-and-checks.md).

## Agent keys

The keys `planningAgent`, `codingAgent` and `reviewAgent` choose which agent
plays each role. They are valid in both files and are single-valued, so a
repeated agent key is an error. The value has the form `harness[:model]` and is
split at the first `:`, which means that a model id containing `:` survives
intact. The `harness` part is one of `claude`, `codex`, `opencode`, `pi` or
`gemini`; any other name is an error that lists the valid ones.

```properties
planningAgent = claude:opus
codingAgent = codex:gpt-5-mini
reviewAgent = opencode:anthropic/claude-haiku-5-5
```

The model part is passed to the harness verbatim, and Orca does not validate
model ids. There is one exception: claude's bare `haiku` alias is sent as
`claude-haiku-5-5`, because the CLI may resolve the bare alias to a pricier
model.

Note that agent keys are read even when `flow(stackSettings = Some(...))` pins
the stack commands. At setup, Orca announces where each role came from:

```text
agents: planning=claude:claude-opus-5-5[1m] (default), coding=codex:gpt-5-mini (project), review=opencode:<harness default> (global)
```

Here `<harness default>` marks a role with no model pin, for which the harness
picks the model itself, and `[1m]` is claude's 1M-token context window variant.

You can set the keys from the command line with
`orca config --coding-agent codex`, or open one of the files in your editor
with `orca config --edit project|global`. Both are described in
[Orca Shell](shell.md).

## Precedence

When a setting is given in more than one place, code always wins over files.
In more detail:

- **Roles:** `flow(planningAgent = ...)` (or `codingAgent` / `reviewAgent`) >
  project file > global file > built-in default (`claude`, no model pin).
- **Stack commands:** `reviewAndFixLoop(formatCommands = Use(...) / Off)` (see
  [Gates and checks](../authoring/gates-and-checks.md)) >
  `flow(stackSettings = Some(...))` > project file > auto-discovery, which
  writes the project file.

## Auto-discovery

When the project file is absent, or has no stack line at all, Orca discovers
the stack commands itself. A file that already has some stack keys is left
alone. Discovery spends one cheap, read-only agent call inspecting the
repository, then writes the file and announces every guess it made:

```text
no .orca/settings.properties — discovering how to format, lint & test this project
  format = cargo fmt   # Cargo.toml; via rustfmt
  lint = cargo check --tests   # Cargo.toml
warning: stack settings: no test command — gate disabled
written to .orca/settings.properties — review and edit as needed.
```

Discovered lines are appended below any existing content, so agent lines are
never touched. To run discovery again, delete the stack lines, delete the whole
file, or run `orca clear-stack`. With a complete file no model call is made at
all; this is the normal case, including in CI.

Each discovered command names the file it was inferred from. Before the file is
written, two checks run on every command: the executable must be on `PATH`,
and the cited file must exist. A command that fails either check is written as
a comment, such as `# skipped: lint = just check (just: not found on PATH)`,
and is never run. A key left with no command gets a live `key = off` line.

```{note}
If discovery itself fails, the run aborts rather than writing a "gates off"
file.
```
