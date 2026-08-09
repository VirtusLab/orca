package orca.runner.terminal

/** The `name: ` prefix that tells apart lines from agents running in parallel
  * (the review fan-out). [[TerminalEventListener]] decides which lines get one.
  */
private[terminal] object AgentAttribution:

  /** Dark-gray, matching the tool-args tone: the name locates the line, the
    * tool name / prose stays the thing being read.
    */
  val Style: fansi.Attrs = fansi.Color.DarkGray

  /** The rendered prefix, or "" when the line needs no attribution. */
  def prefix(
      agent: Option[String],
      paint: (fansi.Attrs, String) => String
  ): String =
    agent.fold("")(name => paint(Style, s"$name: "))
