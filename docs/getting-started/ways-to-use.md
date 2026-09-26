# Ways to use Orca

## Interactively

Run `orca`. A menu lists the flows found in the project, in your global config,
and built in. From it you can run a flow, view or edit its source, create a new
flow with an agent's help, or resume a session left by a previous run.
[Orca Shell](../using/shell.md) has the details.

## Headless, from an agent or CI

Every menu action has a subcommand, so a coding agent or a CI job can run Orca
without a terminal:

```bash
orca run implement.sc "add a rate limiter to /login"
```

`orca run` takes flags that pick the branch or worktree the run works on; they
are listed in [Orca Shell](../using/shell.md#commands) and explained in
[Branches, resume and worktrees](../using/run-lifecycle.md). Which agent
plans, codes and reviews comes from [settings](../using/settings.md).

Agents can load the
[`skills/orca`](https://github.com/VirtusLab/orca/blob/master/skills/orca/SKILL.md)
skill to know when and how to delegate to Orca. In Claude Code, `/orca [prompt]`
asks which flow to run and whether to run it on a new branch, the current
branch or a worktree, then starts it. The skill installs as a Claude Code
plugin, a Pi package, or by symlinking into any harness's skills directory; its
[README](https://github.com/VirtusLab/orca/blob/master/skills/orca/README.md)
has the specifics.

## As a script

A flow is a scala-cli script, so it runs without installing Orca. Only
scala-cli is needed:

```bash
scala-cli run --workspace "$(mktemp -d)" implement.sc -- "add a rate limiter to /login"
```

Orca is published to Maven Central; scala-cli fetches the artifacts on first
run. `--workspace` keeps scala-cli's build output (a `.scala-build` directory)
out of your repository.

This is how the shell runs flows too, so a script that works with `orca run`
works here unchanged.
