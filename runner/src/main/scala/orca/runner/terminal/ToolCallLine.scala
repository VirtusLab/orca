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
    */
  def format(
      name: String,
      rawInput: String,
      paint: (fansi.Attrs, String) => String,
      workDir: Option[os.Path],
      agent: Option[String]
  ): String =
    val args =
      ToolInputSummary.summarise(rawInput, MaxInlineInputLength, workDir)
    val head = paint(ToolNameStyle, s"$ToolCallGlyph ") +
      AgentAttribution.prefix(agent, paint) + paint(ToolNameStyle, name)
    if args.isEmpty then head else head + " " + paint(ToolArgsStyle, args)
