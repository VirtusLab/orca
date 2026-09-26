# Quick start

A flow is a Scala script that tells coding agents what to do. The built-in
`implement.sc` flow plans the work, hands each task to a coding agent, has
every change reviewed by another agent, and opens a pull request. Because the
flow is a program, these steps always happen. Nothing depends on an agent
remembering them.

## What you need

- A logged-in coding-agent CLI, called a [harness](../glossary/users.md#agents-and-conversations):
  `claude`, `codex`, `opencode`, `pi` or `gemini`. See
  [Agent CLIs](../using/agent-clis.md).
- `git`, and `gh` if you want pull requests opened for you.

The installer sets up everything else, including scala-cli and a JVM.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/VirtusLab/orca/master/install.sh | bash
```

This installs `scala-cli` if it is missing and writes the `orca` launcher to
`~/.local/bin/orca`. [Orca Shell](../using/shell.md) describes what the script
does and how to run a pinned version without installing.

## First run

```bash
cd your-project
orca
```

The first run asks which harness and model to use for planning, coding and
review. Then pick a flow (`implement.sc` is first in the list) and enter your
prompt, for example "add a rate limiter to /login".

The same thing without the menu:

```bash
orca run implement.sc "add a rate limiter to /login"
```

## What happens

Orca creates a feature branch, plans the change into tasks, implements each
task and has it reviewed, runs a final review over the whole change, and opens
a PR when the repository is on GitHub. Each step is a **stage**, committed as
it finishes. If the run is interrupted, run the same command again and it
continues from the last commit. [How Orca works](how-it-works.md) has the full
picture.

```{warning}
By default, agents edit files and run shell commands without asking. Run Orca
in a sandbox: see [Run in a sandbox](../using/agent-clis.md#run-in-a-sandbox).
```

## Next steps

- [Ways to use Orca](ways-to-use.md): interactively, from another agent or CI,
  or as a plain script.
- [Built-in flows](../using/built-in-flows.md): what ships with Orca.
- [Writing your first flow](../authoring/tutorial.md): when the built-in flows
  do not fit.
