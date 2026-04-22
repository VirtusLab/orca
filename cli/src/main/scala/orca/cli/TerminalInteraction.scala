package orca.cli

import _root_.orca.{Interaction, InteractiveHandle, OrcaEvent, OrcaListener}
import org.jline.terminal.TerminalBuilder

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
    useColor: Boolean = true,
    animated: Boolean = true
) extends Interaction:

  private val listener = new TerminalListener
  private val listenersList: List[OrcaListener] = List(listener)
  private val spinner: Option[OrcaSpinner] =
    if animated then Some(new OrcaSpinner(out, useColor = useColor)) else None

  def listeners: List[OrcaListener] = listenersList

  /** Hand the terminal to the backend's child process. Snapshots terminal
    * attributes beforehand so any raw/cbreak mode the child leaves behind is
    * restored when control returns. Uses `dumb = true` so the build doesn't
    * fail when there's no TTY (tests, CI, pipes).
    */
  def runInteractive(handle: InteractiveHandle[?]): Unit =
    val terminal = TerminalBuilder
      .builder()
      .system(true)
      .dumb(true)
      .build()
    try
      val savedAttributes = terminal.getAttributes
      out.println(paint(fansi.Color.Yellow, "[entering interactive session]"))
      try
        val _ = handle.awaitTermination()
      finally terminal.setAttributes(savedAttributes)
    finally
      try terminal.close()
      finally
        out.println(paint(fansi.Color.Yellow, "[interactive session ended]"))

  private class TerminalListener extends OrcaListener:
    import TerminalInteraction.*
    def onEvent(event: OrcaEvent): Unit = event match
      case OrcaEvent.StageStarted(name) =>
        out.println(paint(fansi.Color.Cyan, s"$StageStartGlyph $name"))
        spinner.foreach(_.start(name))
      case OrcaEvent.StageCompleted(name, _) =>
        spinner.foreach(_.stop())
        out.println(paint(fansi.Color.Green, s"$StageDoneGlyph $name"))
      case OrcaEvent.LlmOutput(text) =>
        spinner.foreach(_.stop())
        out.print(text)
      case OrcaEvent.ToolUse(tool, args) =>
        spinner.foreach(_.stop())
        out.println(paint(fansi.Color.DarkGray, s"  → $tool: $args"))
      case OrcaEvent.TokensUsed(_) =>
        () // Token accounting is owned by CostTracker.
      case OrcaEvent.Error(message) =>
        spinner.foreach(_.stop())
        out.println(paint(fansi.Color.Red, s"$ErrorGlyph $message"))

  private def paint(attr: fansi.EscapeAttr, text: String): String =
    if useColor then attr(text).render else text

object TerminalInteraction:
  val StageStartGlyph: String = "▶"
  val StageDoneGlyph: String = "✔"
  val ErrorGlyph: String = "✖"
