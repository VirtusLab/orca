# Quick start

A flow is a Scala script that tells coding agents what to do. For example, the
built-in [`implement.sc`](https://github.com/VirtusLab/orca/blob/master/flows/implement.sc)
flow plans the work, hands each task to a coding agent, has every change
reviewed by another agent, and opens a pull request. Because the flow is a
program, these steps always happen; nothing depends on an agent remembering
them.

## What you need

Before you start, make sure you have:

- a coding-agent CLI that you are logged in to. Orca calls such a CLI a
  [harness](../glossary/users.md#agents-and-conversations); the supported ones
  are `claude`, `codex`, `opencode`, `pi` and `gemini`. See
  [Agent CLIs](../using/agent-clis.md) for details.
- `git`, and `gh` if you want Orca to open pull requests for you.

The installer takes care of everything else, including scala-cli and a JVM.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/VirtusLab/orca/master/install.sh | bash
```

The script installs `scala-cli` if it is missing and writes the `orca`
launcher to `~/.local/bin/orca`. If you would rather not install anything, or
want to run a pinned version, [Orca Shell](../using/shell.md) describes what
the script does and how to run the shell directly.

### Install the skill (optional)

The `orca` skill lets a coding agent you already use delegate work to Orca. In
Claude Code:

```text
/plugin marketplace add VirtusLab/orca
/plugin install orca@orca-skills
```

In Pi: `pi install git:github.com/VirtusLab/orca`. For other harnesses, see
[From a coding agent, with the skill](ways-to-use.md#from-a-coding-agent-with-the-skill).

## First run

```bash
cd your-project
orca
```

On the first run, Orca asks which harness and model you want to use for each
of the planning, coding and review roles. It then shows a menu of flows
(`implement.sc` is first in the list); pick one and enter your prompt, for
example "add a rate limiter to /login".

You can skip the menu and do the same thing from the command line:

```bash
orca run implement.sc "add a rate limiter to /login"
```

## What happens

Orca creates a feature branch and plans the change into tasks. Each task is
then implemented and reviewed, a final review runs over the whole change, and,
when the repository is on GitHub, a PR is opened. Each of these steps is a
**stage**, and Orca commits it as soon as it finishes. This is what makes a run
resumable: if it is interrupted, run the same command again and it continues
from the last commit. [How Orca works](how-it-works.md) has the full picture.

```{warning}
By default, agents edit files and run shell commands without asking, so you
should [run Orca in a sandbox](../using/agent-clis.md#run-in-a-sandbox).
```

## Next steps

- [Ways to use Orca](ways-to-use.md) describes how to run Orca interactively,
  from another agent or CI, or as a plain script.
- [Built-in flows](../using/built-in-flows.md) lists what ships with Orca.
- [Writing your first flow](../authoring/tutorial.md) is for when the built-in
  flows do not fit.
