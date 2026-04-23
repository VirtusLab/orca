package orca.runner.terminal

import orca.{
  Backend,
  Conversation,
  Interaction,
  LlmResult,
  OrcaEvent,
  OrcaListener
}

import java.io.PrintStream

/** Terminal-based `Interaction`. Renders stage transitions, tool uses,
  * streaming LLM output, and errors to a `PrintStream` (defaults to
  * stderr so the structured output on stdout stays clean). `drive` is a
  * stub until Task 65 wires the stream-json conversation renderer.
  *
  * Unicode glyphs require a UTF-8 locale; on platforms with a non-UTF-8
  * default charset the caller should pass a PrintStream constructed with
  * `new PrintStream(out, true, "UTF-8")`.
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

  /** Stream-json driver path. Real rendering lands in Task 65; until
    * then, calling `drive` raises. Keeping this as a stub means tests
    * and call sites compile against the new contract now.
    */
  def drive[B <: Backend](conversation: Conversation[B]): LlmResult[B] =
    throw new UnsupportedOperationException(
      "TerminalInteraction.drive is not yet implemented; stream-json UI lands in Task 65"
    )

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
      case OrcaEvent.TokensUsed(_, _) =>
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
