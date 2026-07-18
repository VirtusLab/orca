package orca.shell.flows

/** Extracts a flow's one-line description for the flow-listing menu (ADR 0021 §5). */
object FlowDescription:

  /** The first `//` comment (not a `//>` directive) in the file's leading
    * block of blank lines, `//` comments, and `//>` directives, with the
    * marker and surrounding whitespace stripped. A comment line that strips
    * to nothing is skipped, not treated as the description. Scanning stops
    * at the first line outside that block (code, or a block-style comment),
    * yielding `None`. `lines` is expected to be a bounded prefix of the
    * file; this function performs no IO and does not limit iteration itself.
    */
  def extract(lines: IterableOnce[String]): Option[String] =
    @annotation.tailrec
    def loop(it: Iterator[String]): Option[String] =
      if !it.hasNext then None
      else
        val trimmed = it.next().trim
        if trimmed.isEmpty || trimmed.startsWith("//>") then loop(it)
        else if trimmed.startsWith("//") then
          val text = trimmed.stripPrefix("//").trim
          if text.nonEmpty then Some(text) else loop(it)
        else None

    loop(lines.iterator)
