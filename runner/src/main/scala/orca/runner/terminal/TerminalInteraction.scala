package orca.runner.terminal

import orca.backend.{Conversation, Interaction, AgentResult}
import orca.events.OrcaListener
import orca.agents.BackendTag
import org.slf4j.LoggerFactory
import ox.Ox
import ox.channels.BufferCapacity
import ox.either.orThrow

import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.control.NonFatal

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
  * The default output stream is forced to UTF-8 (see [[start]]): orca's UI is
  * built from non-ASCII glyphs, and when the process is launched under a
  * non-UTF-8 default charset (a `C`/`POSIX` locale — common in containers and
  * editor sandboxes) the JVM's `System.err` would otherwise encode each glyph
  * to `?`.
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
    workDir: Option[os.Path],
    prompter: ConversationRenderer.Prompter
) extends Interaction:

  private val log = LoggerFactory.getLogger(getClass)

  val listeners: List[OrcaListener] = List(listener)

  /** Drive a live conversation to completion on the caller's thread. Returns
    * when the conversation finishes. Backend errors surface as
    * `OrcaInteractiveCancelled` or other throwables from `awaitResult`.
    */
  def drive[B <: BackendTag](conversation: Conversation[B])(using
      Ox
  ): AgentResult[B] =
    new ConversationRenderer(
      useColor = useColor,
      output = output,
      currentIndent = () => listener.currentIndent,
      workDir = workDir,
      structuredMode = conversation.outputSchema.isDefined,
      prompter = prompter
    ).render(conversation).orThrow

  /** Close in construction-reverse order: the prompter (process-scoped, shared
    * across every conversation this interaction drove — renderers never close
    * it) then the output. A test-injected prompter is closed here too; there's
    * no hardcoded singleton in this path.
    *
    * The prompter close is guarded: it's teardown-only degradation, and a
    * throwing prompter must never strand the output (leaving the status row
    * uncleared) or mask whatever error is already unwinding through the
    * caller's `finally`.
    */
  override def close(): Unit =
    try prompter.close()
    catch case NonFatal(e) => log.error("prompter close failed", e)
    output.close()

object TerminalInteraction:

  /** Build a `TerminalInteraction` in the given Ox scope. Inside, the
    * [[TerminalOutput]]'s actor and animator fork are tied to the scope and
    * terminate when it ends. `flow(...)` calls [[close]] in its `finally`
    * before the scope joins, draining pending writes.
    */
  def start(
      out: PrintStream = utf8Stderr,
      useColor: Boolean = defaultUseColor,
      animated: Boolean = defaultAnimated,
      workDir: Option[os.Path] = None,
      prompter: ConversationRenderer.Prompter =
        ConversationRenderer.JLinePrompter
  )(using Ox, BufferCapacity): TerminalInteraction =
    val output = TerminalOutput.start(out, useColor, animated)
    val listener = new TerminalEventListener(output, useColor, workDir)
    new TerminalInteraction(output, listener, useColor, workDir, prompter)

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

  /** `System.err`, re-encoded as UTF-8 regardless of the JVM's default charset.
    * orca's UI is built from non-ASCII glyphs (`…`, `✖`, `▸`, `●`, braille
    * spinner); when launched under a non-UTF-8 locale (`C`/`POSIX`, common in
    * containers and editor sandboxes) the JVM resolves `stderr.encoding` to
    * US-ASCII and `System.err` would encode each glyph to `?`. Wrapping forces
    * UTF-8 char→byte encoding; the bytes pass through the underlying
    * `System.err` unchanged (`PrintStream.write(byte[])` doesn't re-encode).
    * Never closed by [[TerminalOutput]] (it only prints/flushes), so the
    * underlying `System.err` stays open.
    */
  private[terminal] def utf8Stderr: PrintStream =
    new PrintStream(System.err, true, UTF_8)

  private def consolePresent: Boolean = System.console() != null

  private def ciDetected: Boolean =
    sys.env.get("CI").exists(_.nonEmpty)
