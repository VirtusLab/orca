# Orca Shell

Orca Shell is the `orca` command: an interactive terminal front-end for flow
scripts, plus a scriptable subcommand for every action in its menu.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/VirtusLab/orca/master/install.sh | bash
```

The script does two things:

1. If `scala-cli` is not on your `PATH`, it runs scala-cli's official
   installer. scala-cli then manages its own JVM.
2. It writes the `orca` launcher to `~/.local/bin/orca`. The launcher runs the
   latest released `orca-shell` via `scala-cli`; the artifacts are downloaded
   on the first `orca` run. The launcher itself never needs updating.

If the installer says that `~/.local/bin` is not on your `PATH`, add it, then
run `orca`.

If you would rather not install anything, or you want to pin a version (for
example in CI), you can run the shell directly with scala-cli. The version
below tracks the latest release, but any release that includes the shell
works. The `--workspace` option keeps scala-cli's build metadata out of the
current directory:

```bash
scala-cli run --workspace "${XDG_CACHE_HOME:-$HOME/.cache}/orca/shell/workspace" --jvm 21 --quiet --verbose --dep "org.virtuslab::orca-shell:0.1.10" --main-class orca.shell.Main
```

## The interactive shell

On the first run, a wizard asks you to pick a
[harness](../glossary/users.md#agents-and-conversations) and model for each of
the planning, coding and review roles, and writes them to the global
`settings.properties` (see [Settings](settings.md)). After that, a menu lets
you:

- discover flows: project, global and built-in
- run a flow
- view or edit a flow's source
- create a new flow, or fork an existing one, with the configured agents' help
- continue a session left by a previous run

## Commands

`orca` with no arguments starts the interactive shell. `orca <command> ...`
runs a single action and exits, which is what you want from a script or a CI
job. The commands are:

- `orca run <flow> [prompt]` runs a flow and exits with the flow's exit code.
  When no prompt is given, it is read from stdin. The flags are described
  below.
- `orca view <flow>` prints a flow's source, highlighted when stdout is a
  terminal (see also `--plain` and `--color`).
- `orca edit <flow>` opens a flow in `$VISUAL`, `$EDITOR` or `vi`. To
  customise a built-in flow, `--to project` or `--to global` is required.
- `orca create "<goal>"` runs the built-in `quick.sc` flow in an isolated
  sandbox to have the configured agents write a new flow. `--name <file>`
  sets the file name, which is otherwise derived, and `--global` makes it a
  global flow.
- `orca fork <source> "<changes>"` does the same, but starts from an existing
  flow. It takes the same `--name` and `--global` flags.
- `orca continue [selector]` resumes a recorded harness session; with no
  selector, the newest one. `--list` shows the sessions, as JSON with
  `--json`.
- `orca config` shows the role agents. `--planning-agent`, `--coding-agent`
  and `--review-agent`, each taking `harness[:model]`, set any subset of them.
  Alternatively, `--edit project|global` opens a settings file for
  hand-editing, creating it from a template if it does not exist.
- `orca list` lists project, global and built-in flows, as JSON with `--json`.
- `orca clear-stack` forgets the detected `format` / `lint` / `test` commands,
  so that the next run re-detects them (see [Settings](settings.md)). With
  `--yes` it works without a terminal.

### Flags of `orca run`

| Flag | Effect |
|---|---|
| `--prompt <text>` | the prompt, for text starting with `-`; not together with the positional prompt |
| `--branch <name>` | name the branch the run creates; refused with `--skip-branch` |
| `--skip-branch` | continue on the current branch instead of creating one |
| `--keep-changes` | leave uncommitted files in place instead of stashing them |
| `--worktree` | run in a git worktree of this repository instead of the checkout |
| `--honor-pin` | use the flow's own pinned Orca version |
| `--verbose` | print a stack trace on abort |

[Branches, resume and worktrees](run-lifecycle.md) explains the branch flags.

### Selecting a session to continue

The selector of `orca continue` is an id from `--list`, a session name, or a
branch. An id keeps naming the same session while other attempts record
theirs. If a name matches several sessions in one working tree, the most
recent one is resumed. A selector that matches both a name and a branch is
refused.

### Running without a terminal

Note that the authoring sandbox of `create` and `fork` is a fresh repository
with no remote, so the flow's closing PR step opens nothing and says so.

`create`, `fork`, `edit`, `continue` when it resumes a session, and
`config --edit` need a real terminal, and error cleanly without one. `run`,
`view`, `list`, `config` without `--edit`, and `clear-stack --yes` work piped
or in CI.

### Examples

```bash
orca run implement.sc "add a rate limiter to /login"
echo "add a rate limiter" | orca run implement.sc
orca list --json | jq -r '.[].name'
orca create "add a token-bucket limiter" --name rate-limit.sc
orca continue              # resume the last session
orca continue --list
orca continue feat/rate-limiter
orca config --coding-agent codex
orca config --review-agent claude:sonnet
orca view implement.sc
```

`orca --help` lists every command, and `orca <command> --help` shows a
command's flags.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | action failure |
| 2 | usage error |

`orca run` exits with the flow's own exit code, so a failed run fails a CI job.
