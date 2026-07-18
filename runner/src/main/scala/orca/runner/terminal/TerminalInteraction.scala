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
  * The output has two zones, both owned by [[TerminalOutput]]: an **event log**
  * growing line-by-line at the top, and a **status line** with an animated
  * spinner pinned at the bottom. When stderr isn't a TTY (CI, redirected
  * output, `NO_COLOR`/`ORCA_NO_ANIMATION`) it degrades to plain inline writes.
  *
  * The default stream is forced to UTF-8 (see [[start]]) so orca's non-ASCII
  * glyphs survive a non-UTF-8 default charset. `drive` runs on the caller's
  * thread; the spinner advances on a separate fork inside `TerminalOutput`
  * while drive blocks on the backend. [[close]] runs from `flow(...)`'s
  * `finally` to flush and clear the status row before the scope ends.
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

  /** Close the prompter (shared across every conversation; renderers never
    * close it), then the output. The prompter close is guarded so a throwing
    * prompter can't strand the output uncleared or mask an error already
    * unwinding through the caller's `finally`.
    */
  override def close(): Unit =
    try prompter.close()
    catch case NonFatal(e) => log.error("prompter close failed", e)
    output.close()

object TerminalInteraction:

  /** Build a `TerminalInteraction` in the given Ox scope. The
    * [[TerminalOutput]]'s actor and animator fork are tied to the scope and
    * terminate when it ends.
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
    * Under a non-UTF-8 locale (`C`/`POSIX`, common in containers/sandboxes) the
    * JVM resolves `stderr.encoding` to US-ASCII and would encode orca's
    * non-ASCII glyphs to `?`; wrapping forces UTF-8 encoding. Never closed by
    * [[TerminalOutput]], so the underlying `System.err` stays open.
    */
  private[terminal] def utf8Stderr: PrintStream =
    new PrintStream(System.err, true, UTF_8)

  private def consolePresent: Boolean = System.console() != null

  private def ciDetected: Boolean =
    sys.env.get("CI").exists(_.nonEmpty)
