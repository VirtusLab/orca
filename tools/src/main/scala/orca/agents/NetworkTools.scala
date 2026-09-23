package orca.agents

/** Claude built-in tools added to the read-only `--tools` allowlist on
  * [[ToolSet.NetworkOnly]] turns. Built by `claude.withNetworkTools(...)`,
  * which validates the names; other backends ignore it.
  */
opaque type NetworkTools = Seq[String]

object NetworkTools:
  private[orca] def apply(names: Seq[String]): NetworkTools = names

  extension (t: NetworkTools) def names: Seq[String] = t
