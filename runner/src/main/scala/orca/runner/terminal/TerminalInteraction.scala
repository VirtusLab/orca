package orca.runner.terminal

import orca.backend.{Conversation, Interaction, LlmResult}
import orca.events.OrcaListener
import orca.llm.BackendTag
import ox.Ox
import ox.channels.BufferCapacity
import ox.either.orThrow

import java.io.PrintStream

/** Terminal-based `Interaction`. Renders stage transitions, tool uses,
  * streaming LLM output, and errors to a `PrintStream` (defaults to stderr so
  * the structured output on stdout stays clean).
  *
  * The output is split in two zones:
  *   - The **event log** at the top, growing line-by-line as stages start and
  *     tools fire.
  *   - A **status line** pinned at the bottom, showing the current activity
  *     with an animated spinner glyph.
  *
  * Both zones are owned by [[TerminalOutput]], which serialises every write on
  * a single worker thread. When stderr isn't a TTY (CI, redirected output,
  * `NO_COLOR`/`ORCA_NO_ANIMATION`), the output degrades to plain inline writes
  * without ANSI escapes.
  *
  * Unicode glyphs require a UTF-8 locale; on platforms with a non-UTF-8 default
  * charset the caller should pass a PrintStream constructed with `new
  * PrintStream(out, true, "UTF-8")`.
  *
  * `drive` runs on the caller's thread (no actor ask). It iterates the
  * conversation's event stream and tells the output. The spinner runs on a
  * separate fork inside `TerminalOutput`, so it keeps advancing while drive
  * blocks on the backend.
  *
  * [[close]] is called from `flow(...)`'s `finally` to flush pending writes and
  * clear the status row before the enclosing scope ends.
  */
class TerminalInteraction private[terminal] (
    output: TerminalOutput,
    listener: TerminalEventListener,
    useColor: Boolean,
    workDir: Option[os.Path]
) extends Interaction:

  val listeners: List[OrcaListener] = List(listener)

  /** Drive a live conversation to completion on the caller's thread. Returns
    * when the conversation finishes. Backend errors surface as
    * `OrcaInteractiveCancelled` or other throwables from `awaitResult`.
    */
  def drive[B <: BackendTag](conversation: Conversation[B]): LlmResult[B] =
    new ConversationRenderer(
      useColor = useColor,
      output = output,
      currentIndent = () => listener.currentIndent,
      workDir = workDir,
      structuredMode = conversation.outputSchema.isDefined
    ).render(conversation).orThrow

  override def close(): Unit = output.close()

object TerminalInteraction:

  /** Build a `TerminalInteraction` in the given Ox scope. Inside, the
    * [[TerminalOutput]]'s actor and animator fork are tied to the scope and
    * terminate when it ends. `flow(...)` calls [[close]] in its `finally`
    * before the scope joins, draining pending writes.
    */
  def start(
      out: PrintStream = System.err,
      useColor: Boolean = defaultUseColor,
      animated: Boolean = defaultAnimated,
      workDir: Option[os.Path] = None
  )(using Ox, BufferCapacity): TerminalInteraction =
    val output = TerminalOutput.start(out, useColor, animated)
    val listener = new TerminalEventListener(output, useColor)
    new TerminalInteraction(output, listener, useColor, workDir)

  /** ANSI colors default off when stderr isn't attached to a terminal (no
    * controlling console), the `NO_COLOR` convention is honoured, or we detect
    * a CI runner.
    */
  def defaultUseColor: Boolean =
    !sys.env.contains("NO_COLOR") && consolePresent && !ciDetected

  /** Animation is strictly a subset of colour — it additionally writes
    * cursor-control escapes in a tight loop, so suppressing it when we suspect
    * the output is being captured is doubly important.
    */
  def defaultAnimated: Boolean =
    defaultUseColor && !sys.env.contains("ORCA_NO_ANIMATION")

  private def consolePresent: Boolean = System.console() != null

  private def ciDetected: Boolean =
    sys.env.get("CI").exists(_.nonEmpty)
