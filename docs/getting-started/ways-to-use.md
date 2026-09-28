# Ways to use Orca

## Interactively

The simplest way to start is to run `orca` with no arguments. You get a menu
that lists the flows found in the project, in your global config, and built
into Orca. From the menu you can run a flow, view or edit its source, create a
new flow with an agent's help, or resume a session left by a previous run.
[Orca Shell](../using/shell.md) describes the shell in detail.

## Headless, from the command line

Every menu action also has a subcommand, so a script or a CI job can run Orca
without a terminal. For example, to run the `implement.sc` flow with a prompt:

```bash
orca run implement.sc "add a rate limiter to /login"
```

`orca run` takes flags that pick the branch or worktree the run works on. They
are listed in [Orca Shell](../using/shell.md#commands) and explained in
[Branches, resume and worktrees](../using/run-lifecycle.md). Note that which
agent plans, codes and reviews is not passed on the command line: it comes from
[settings](../using/settings.md).

## From a coding agent, with the skill

If you already work in a coding agent, it can delegate work to Orca. The
[`skills/orca`](https://github.com/VirtusLab/orca/blob/master/skills/orca/SKILL.md)
skill tells the agent when and how to do that. In Claude Code, for example,
`/orca [prompt]` asks which flow to run and whether to run it on a new branch,
the current branch or a worktree, and then starts it.

To install the skill into your harness:

- **Claude Code**: run `/plugin marketplace add VirtusLab/orca`, then
  `/plugin install orca@orca-skills`. Alternatively, copy or symlink the
  `skills/orca` directory to `~/.claude/skills/orca`.
- **Pi**: run `pi install git:github.com/VirtusLab/orca`.
- **OpenCode**: copy or symlink `skills/orca` to `~/.config/opencode/skills/orca`.
- **Codex**: copy or symlink `skills/orca` to `~/.agents/skills/orca`.

The skill's
[README](https://github.com/VirtusLab/orca/blob/master/skills/orca/README.md)
has the per-project variants of these paths.

## As a script

A flow is a scala-cli script, so you do not need to install Orca to run one;
scala-cli alone is enough:

```bash
scala-cli run --workspace "$(mktemp -d)" implement.sc -- "add a rate limiter to /login"
```

Orca is published to Maven Central, and scala-cli fetches the artifacts on the
first run. The `--workspace` option keeps scala-cli's build output (a
`.scala-build` directory) out of your repository.

Note that this is exactly how the shell runs flows too, so a script that works
with `orca run` works here unchanged.
