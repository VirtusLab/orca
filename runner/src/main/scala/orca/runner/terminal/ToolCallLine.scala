package orca.runner.terminal

/** Formatter for the one-line tool-call summary [[TerminalEventListener]]
  * prints. Returns the head (`⏺ name`) plus an optional styled args tail; the
  * caller adds the indent.
  */
private[terminal] object ToolCallLine:
  import ToolInputSummary.MaxInlineInputLength

  private val ToolCallGlyph: String = "⏺"

  // Yellow-bold so "doing something external" stands apart from the
  // magenta-bold prose and step accent; the args are secondary, so dark-gray.
  private val ToolNameStyle: fansi.Attrs = fansi.Color.Yellow ++ fansi.Bold.On
  private val ToolArgsStyle: fansi.Attrs = fansi.Color.DarkGray

  /** `agent` names the emitting agent when the line needs attributing (see
    * [[AgentAttribution]]); `None` renders the bare `⏺ name (args)` form.
    * `indent` is the stage indent the caller will prepend — passed rather than
    * applied here, since everything ahead of the args eats into their width
    * budget ([[LineBudget]]).
    */
  def format(
      name: String,
      rawInput: String,
      paint: (fansi.Attrs, String) => String,
      workDir: Option[os.Path],
      agent: Option[String],
      indent: String
  ): String =
    val budget = LineBudget.remaining(
      MaxInlineInputLength,
      indent.length,
      LineBudget.glyphWidth(ToolCallGlyph),
      AgentAttribution.width(agent),
      name.length + 1 // the space between the tool name and its args
    )
    val args = ToolInputSummary.summarise(rawInput, budget, workDir)
    val head = paint(ToolNameStyle, s"$ToolCallGlyph ") +
      AgentAttribution.prefix(agent, paint) + paint(ToolNameStyle, name)
    if args.isEmpty then head else head + " " + paint(ToolArgsStyle, args)
