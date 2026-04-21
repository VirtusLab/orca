package orca.cli

import orca.*

import java.io.PrintStream

/** Terminal-based `Interaction`. Renders stage transitions, tool uses,
  * streaming LLM output, and errors to a `PrintStream` (defaults to stderr so
  * the structured output on stdout stays clean). `runInteractive` relies on
  * backends having spawned the child with inherited stdio, so nothing more is
  * needed here than awaiting termination.
  *
  * Unicode glyphs require a UTF-8 locale; on platforms with a non-UTF-8 default
  * charset the caller should pass a PrintStream constructed with `new
  * PrintStream(out, true, "UTF-8")`.
  */
class TerminalInteraction(
    out: PrintStream = System.err,
    useColor: Boolean = true
) extends Interaction:

  private val listener = new TerminalListener
  private val listenersList: List[OrcaListener] = List(listener)

  def listeners: List[OrcaListener] = listenersList

  def runInteractive(handle: InteractiveHandle[?]): Unit =
    out.println(paint(fansi.Color.Yellow, "[entering interactive session]"))
    try
      val _ = handle.awaitTermination()
    finally
      out.println(paint(fansi.Color.Yellow, "[interactive session ended]"))

  private class TerminalListener extends OrcaListener:
    import TerminalInteraction.*
    def onEvent(event: OrcaEvent): Unit = event match
      case OrcaEvent.StageStarted(name) =>
        out.println(paint(fansi.Color.Cyan, s"$StageStartGlyph $name"))
      case OrcaEvent.StageCompleted(name, _) =>
        out.println(paint(fansi.Color.Green, s"$StageDoneGlyph $name"))
      case OrcaEvent.LlmOutput(text) =>
        out.print(text)
      case OrcaEvent.ToolUse(tool, args) =>
        out.println(paint(fansi.Color.DarkGray, s"  → $tool: $args"))
      case OrcaEvent.TokensUsed(_) =>
        () // Token accounting is owned by CostTracker.
      case OrcaEvent.Error(message) =>
        out.println(paint(fansi.Color.Red, s"$ErrorGlyph $message"))

  private def paint(attr: fansi.EscapeAttr, text: String): String =
    if useColor then attr(text).render else text

object TerminalInteraction:
  val StageStartGlyph: String = "▶"
  val StageDoneGlyph: String = "✔"
  val ErrorGlyph: String = "✖"
