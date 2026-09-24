package orca.tools.claude

import orca.agents.NetworkTools

/** The checks behind `claude.withNetworkTools`. */
private[claude] object ClaudeNetworkTools:

  /** Validated `tools`. Throws `IllegalArgumentException` for anything that is
    * not a bare tool name, or that is a write-capable builtin.
    *
    * Bare names only: `--tools` silently ignores anything else (exit 0, no
    * warning), so a command-scoped entry like `Bash(gh api:*)` would grant
    * nothing.
    *
    * No write-capable builtins: a `NetworkOnly` turn puts these names on both
    * `--tools` and `--allowedTools`, so passing one here hands back an
    * auto-approved shell while the tier still reports `Hard`.
    */
  def validated(tools: Seq[String]): NetworkTools =
    rejectNonBareNames(tools)
    rejectWriteCapable(tools)
    NetworkTools(tools)

  /** What `--tools` accepts: a bare built-in name. Not MCP names — those pass
    * `--tools` unfiltered, so listing one there does nothing.
    */
  private val BareToolName = "[A-Za-z][A-Za-z0-9]*".r

  /** Builtins refused: each writes, shells out, or drives a shell. Probed
    * 2026-08-08, claude 2.1.226: only `Bash`, `Monitor`, `Write`, `Edit` and
    * `NotebookEdit` are in the built-in set; the rest are kept because a stale
    * name here is harmless while a missing one is not.
    */
  private val WriteCapableTools: Set[String] = Set(
    "Bash",
    "BashOutput",
    "KillBash",
    "KillShell",
    "Monitor",
    "Write",
    "Edit",
    "MultiEdit",
    "NotebookEdit"
  )

  private def rejectNonBareNames(tools: Seq[String]): Unit =
    val bad = tools.filterNot(BareToolName.matches)
    if bad.nonEmpty then
      throw new IllegalArgumentException(
        "withNetworkTools takes bare claude tool names; these are not: " +
          s"${bad.mkString(", ")}. --tools silently ignores command-scoped " +
          "entries like \"Bash(gh api:*)\"."
      )

  private def rejectWriteCapable(tools: Seq[String]): Unit =
    val bad = tools.filter(WriteCapableTools.contains)
    if bad.nonEmpty then
      throw new IllegalArgumentException(
        "withNetworkTools adds network reads to a NetworkOnly turn; these " +
          s"write or shell out: ${bad.mkString(", ")}. Use ToolSet.Full if " +
          "the agent needs to write or run commands."
      )
