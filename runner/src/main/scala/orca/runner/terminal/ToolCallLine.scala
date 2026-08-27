package orca.runner.terminal

/** Shared formatter for the one-line tool-call summary, used by both
  * [[TerminalEventListener]] and [[ConversationRenderer]] so the two render
  * paths can't drift on glyph, styling, or summarisation. Returns the head (`⏺
  * name`) plus an optional styled args tail; the caller adds the indent.
  */
private[terminal] object ToolCallLine:
  import ConversationRenderer.{
    MaxInlineInputLength,
    ToolArgsStyle,
    ToolCallGlyph,
    ToolNameStyle
  }

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
