package orca.shell.cli

import orca.shell.ShellVersion

/** The curated top-level `orca --help` text (ADR 0021 §10/§5) — mainargs' own
  * auto-generated no-subcommand dump lists every method/arg flat with no
  * synopsis or examples, so this is hand-rolled instead. Per-subcommand help
  * (`orca <command> --help`) stays mainargs-generated (`Cli.commandHelp`).
  */
private[shell] object CliHelp:

  val topLevel: String =
    s"""orca shell ${ShellVersion.value} — run, author, and manage Orca flows
       |
       |Usage:
       |  orca                    start the interactive shell
       |  orca <command> [args]   run one action non-interactively
       |
       |Commands:
       |  run <flow> [task]        run a flow (task from stdin when piped)
       |  view <flow>              print a flow's source
       |  edit <flow>              open a flow in $$EDITOR (built-ins: --to project|global)
       |  create [name]            author a new flow with a coding agent (--goal required)
       |  fork <source> [name]     fork an existing flow (--changes required)
       |  continue [session]       resume a recorded harness session (--list to see them)
       |  list                     list discovered flows across all tiers
       |  config                   show or set role agents (--planning/--coding/--review)
       |  rediscover-stack         clear discovered stack settings (--yes to confirm)
       |
       |Examples:
       |  orca run implement.sc "add pagination to the user list"
       |  git diff | orca run review.sc
       |  orca create --goal "summarize a PR's review threads" --harness claude
       |  orca continue --list
       |  orca config --coding codex
       |
       |Run 'orca <command> --help' for details. 'orca --version' prints the version.""".stripMargin
