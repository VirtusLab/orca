package orca.tools

/** Regex for a bare hostname: dot-separated labels of letters, digits and `-`.
  * Hosts are spliced into git config keys, `gh` arguments and browser URLs, so
  * userinfo, a port, `=`, whitespace and `..` all fail it.
  */
private[tools] val HostName: String = """[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*"""
