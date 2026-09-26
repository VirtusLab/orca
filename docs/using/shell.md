# Orca Shell

Orca Shell is the `orca` command: an interactive terminal front-end for flow
scripts, plus a scriptable subcommand for every action in its menu.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/VirtusLab/orca/master/install.sh | bash
```

The script does two things:

1. If `scala-cli` is not on your `PATH`, it runs scala-cli's official installer.
   scala-cli then manages its own JVM.
2. It writes the `orca` launcher to `~/.local/bin/orca`. The launcher runs the
   latest released `orca-shell` via `scala-cli`. The artifacts are downloaded
   on the first `orca` run. The launcher itself never needs updating.

Add `~/.local/bin` to your `PATH` if the installer says it is not there, then
run `orca`.

To avoid installing anything, or to pin a version (for example in CI), run the
shell directly. The version below tracks the latest release; any release that
includes the shell works. `--workspace` keeps scala-cli's build metadata out of
the current directory:

```bash
scala-cli run --workspace "${XDG_CACHE_HOME:-$HOME/.cache}/orca/shell/workspace" --jvm 21 --quiet --verbose --dep "org.virtuslab::orca-shell:0.1.10" --main-class orca.shell.Main
```

## The interactive shell

On first run a wizard picks a [harness](../glossary/users.md#agents-and-conversations)
and model for each of the planning, coding and review roles, and writes them
to the global `settings.properties` (see [Settings](settings.md)). Then a menu
lets you:

- discover flows: project, global and built-in
- run a flow
- view or edit a flow's source
- create a new flow, or fork an existing one, with the configured agents' help
- continue a session left by a previous run

## Commands

`orca` with no arguments starts the interactive shell. `orca <command> ...`
runs one action and exits.

| Command | Key flags | Does |
|---|---|---|
| `orca run <flow> [prompt]` | see below | run a flow; exits with the flow's exit code; with no prompt, reads it from stdin |
| `orca view <flow>` | `--plain`, `--color` | print a flow's source, highlighted when stdout is a terminal |
| `orca edit <flow>` | `--to project\|global` | open a flow in `$VISUAL` / `$EDITOR` / `vi`; `--to` is required to customise a built-in |
| `orca create "<goal>"` | `--name <file>`, `--global` | run the built-in `simple.sc` flow in an isolated sandbox to have the configured agents write a new flow; `--name` is derived when omitted |
| `orca fork <source> "<changes>"` | `--name <file>`, `--global` | the same, starting from an existing flow |
| `orca continue [selector]` | `--list`, `--json` | resume a recorded harness session; no selector means the newest |
| `orca config` | `--planning-agent`, `--coding-agent`, `--review-agent`, each `harness[:model]`; or `--edit project\|global` | show the role agents, set any subset, or hand-edit a settings file, created from a template if absent |
| `orca list` | `--json` | list project, global and built-in flows |
| `orca clear-stack` | `--yes` | forget the detected `format` / `lint` / `test` commands so the next run re-detects them, see [Settings](settings.md) |

Flags of `orca run`:

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

`orca continue`'s selector is an id from `--list`, a session name, or a branch.
An id keeps naming the same session while other attempts record theirs. A name
matching several sessions in one working tree resumes the most recent. A
selector matching both a name and a branch is refused.

The authoring sandbox of `create` and `fork` is a fresh repository with no
remote, so the flow's closing PR step opens nothing and says so.

`create`, `fork`, `edit`, `continue` when it resumes a session, and
`config --edit` need a real terminal and error cleanly without one. `run`,
`view`, `list`, `config` without `--edit`, and `clear-stack --yes` work piped
or in CI.

Examples:

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

`orca --help` lists every command; `orca <command> --help` shows a command's
flags.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | action failure |
| 2 | usage error |

`orca run` exits with the flow's own exit code, so a failed run fails a CI job.
