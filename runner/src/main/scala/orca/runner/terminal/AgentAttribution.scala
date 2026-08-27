package orca.runner.terminal

/** The `name: ` prefix that tells apart lines from agents running in parallel
  * (the review fan-out). [[TerminalEventListener]] decides which lines get one.
  */
private[terminal] object AgentAttribution:

  /** Dark-gray, matching the tool-args tone: the name locates the line, the
    * tool name / prose stays the thing being read.
    */
  val Style: fansi.Attrs = fansi.Color.DarkGray

  /** The prefix as the reader sees it, before painting — the one place its
    * shape is decided.
    */
  private def plain(name: String): String = s"$name: "

  /** The rendered prefix, or "" when the line needs no attribution. */
  def prefix(
      agent: Option[String],
      paint: (fansi.Attrs, String) => String
  ): String =
    agent.fold("")(name => paint(Style, plain(name)))

  /** Columns [[prefix]] takes — 0 when the line isn't attributed. Measured on
    * the unpainted form: ANSI escapes take no columns.
    */
  def width(agent: Option[String]): Int = agent.fold(0)(plain(_).length)
