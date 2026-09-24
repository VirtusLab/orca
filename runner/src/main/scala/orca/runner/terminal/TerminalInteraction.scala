package orca.runner.terminal

import orca.backend.{AgentResult, Interaction, ObservedTurn}
import orca.events.OrcaListener
import orca.agents.BackendTag
import ox.Ox
import ox.channels.BufferCapacity

import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8

/** Terminal-based `Interaction`. Renders stage transitions, tool uses,
  * streaming LLM output, and errors to a `PrintStream` (defaults to stderr so
  * the structured output on stdout stays clean).
  *
  * The output has two zones, both owned by [[TerminalActor]]: an **event log**
  * growing line-by-line at the top, and a **status line** with an animated
  * spinner pinned at the bottom. When stderr isn't a TTY (CI, redirected
  * output, `NO_COLOR`/`ORCA_NO_ANIMATION`) it degrades to plain inline writes.
  *
  * The default stream is forced to UTF-8 (see [[TerminalInteraction.start]]) so
  * orca's non-ASCII glyphs survive a non-UTF-8 default charset. `drive` runs on
  * the caller's thread; the spinner advances on a separate fork inside
  * `TerminalActor` while drive blocks on the backend. [[close]] runs from
  * `flow(...)`'s `finally` to flush and clear the status row before the scope
  * ends.
  */
class TerminalInteraction private[terminal] (
    output: TerminalActor,
    useColor: Boolean,
    workDir: Option[os.Path],
    prompter: TerminalPrompts.Prompter
) extends Interaction:

  val listeners: List[OrcaListener] = List(output.listener)

  /** Drive a live turn to completion on the caller's thread, prompting for its
    * approvals and questions. Returns when the turn finishes. Backend errors
    * surface as `OrcaInteractiveCancelled` or other throwables.
    */
  def drive[B <: BackendTag](
      turn: ObservedTurn[B]
  ): AgentResult[B] =
    new TerminalPrompts(
      useColor = useColor,
      output = output,
      currentIndent = () => output.currentIndent,
      workDir = workDir,
      prompter = prompter
    ).drive(turn)

  override def close(): Unit = output.close()

object TerminalInteraction:

  /** Build a `TerminalInteraction` in the given Ox scope. The
    * [[TerminalActor]]'s actor and animator fork are tied to the scope and
    * terminate when it ends.
    */
  def start(
      out: PrintStream = utf8Stderr,
      useColor: Boolean = defaultUseColor,
      animated: Boolean = defaultAnimated,
      workDir: Option[os.Path] = None,
      prompter: TerminalPrompts.Prompter = TerminalPrompts.JLinePrompter
  )(using Ox, BufferCapacity): TerminalInteraction =
    new TerminalInteraction(
      TerminalActor.start(
        out = out,
        useColor = useColor,
        animated = animated,
        workDir = workDir
      ),
      useColor,
      workDir,
      prompter
    )

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
  private[orca] def utf8Stderr: PrintStream =
    new PrintStream(System.err, true, UTF_8)

  private def consolePresent: Boolean = System.console() != null

  private def ciDetected: Boolean =
    sys.env.get("CI").exists(_.nonEmpty)
