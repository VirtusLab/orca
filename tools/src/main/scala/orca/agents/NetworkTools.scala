package orca.agents

/** Claude built-in tools added to the read-only `--tools` allowlist on
  * [[ToolSet.NetworkOnly]] turns, set on a claude agent with
  * `claude.withNetworkTools(...)`. Other backends ignore it.
  */
opaque type NetworkTools = Seq[String]

object NetworkTools:
  extension (t: NetworkTools) def names: Seq[String] = t

  /** Validated `tools`. Throws `IllegalArgumentException` for anything that is
    * not a bare tool name, or that is a write-capable builtin.
    *
    * Bare names only: these used to be `--allowedTools` patterns and could be
    * command-scoped (`Bash(gh api:*)`); `--tools` takes bare names and drops
    * what it does not recognise silently, exit 0, no warning. Without this
    * check a flow script carrying the old syntax would keep compiling, keep
    * running, and grant nothing.
    *
    * No write-capable builtins: a `NetworkOnly` turn puts these names on both
    * `--tools` and `--allowedTools`, so passing one here hands back an
    * auto-approved shell while the tier still reports `Hard`.
    */
  def apply(tools: Seq[String]): NetworkTools =
    rejectNonBareNames(tools)
    rejectWriteCapable(tools)
    tools

  /** What `--tools` accepts: a bare built-in name. Not MCP names — those pass
    * `--tools` unfiltered, so listing one there does nothing.
    */
  private val BareToolName = "[A-Za-z][A-Za-z0-9]*".r

  /** Builtins refused: each writes, shells out, or drives a shell, and network
    * tools exist only to add network reads. Probed 2026-08-08, claude 2.1.226:
    * only `Bash`, `Monitor`, `Write`, `Edit` and `NotebookEdit` are still in
    * the built-in set; the rest are kept because a stale name here is harmless
    * while a missing one is not.
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
          s"${bad.mkString(", ")}. Command-scoped entries like " +
          "\"Bash(gh api:*)\" belonged to the old --allowedTools mapping and " +
          "are silently ignored by --tools."
      )

  private def rejectWriteCapable(tools: Seq[String]): Unit =
    val bad = tools.filter(WriteCapableTools.contains)
    if bad.nonEmpty then
      throw new IllegalArgumentException(
        "withNetworkTools adds network reads to a NetworkOnly turn; these " +
          s"write or shell out: ${bad.mkString(", ")}. Use ToolSet.Full if " +
          "the agent needs to write or run commands."
      )
