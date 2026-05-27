package orca.runner.terminal

/** Shared formatter for the one-line tool-call summary used by both the
  * autonomous-path [[TerminalEventListener]] and the interactive-path
  * [[ConversationRenderer]]. Returns the head (`⏺ name`) plus an optional
  * styled args tail; the caller is responsible for the surrounding stage-depth
  * indent.
  *
  * Lives here so the two render paths can't drift on glyph, styling, or
  * summarisation rules without one change point.
  */
private[terminal] object ToolCallLine:
  import ConversationRenderer.{
    MaxInlineInputLength,
    ToolArgsStyle,
    ToolCallGlyph,
    ToolNameStyle
  }

  def format(
      name: String,
      rawInput: String,
      paint: (fansi.Attrs, String) => String,
      workDir: Option[os.Path]
  ): String =
    val args =
      ToolInputSummary.summarise(rawInput, MaxInlineInputLength, workDir)
    val head = paint(ToolNameStyle, s"$ToolCallGlyph $name")
    if args.isEmpty then head else head + " " + paint(ToolArgsStyle, args)
