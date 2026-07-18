package orca.shell.run

import org.jline.terminal.Terminal
import sun.misc.{Signal, SignalHandler}

/** Brackets a foreground child process (a flow run or an editor invocation)
  * around the shell's own JLine [[Terminal]] (ADR 0021 §2). Attributes are
  * saved before `body` and restored in `finally` — a child that crashes
  * mid-`readLine` in raw mode must not leave the shell's next prompt wedged.
  *
  * Deliberately does NOT call `pause()`/`resume()`. Verified against the jline
  * 3.30.15 jar: the production terminal (`PosixSysTerminal`, from
  * `TerminalBuilder.builder().system(true)`) doesn't override them, so they'd
  * be no-ops on the shell's actual startup path. On terminals that do override
  * them (`ExternalTerminal`, `PosixPtyTerminal`, the Windows console terminal)
  * — which own a background pump thread reading raw input — pausing for the
  * whole bracket would also suspend that thread across the mid-bracket
  * `ui.confirm` fallback prompt (ADR 0021 §2), which reads through this same
  * [[Terminal]] and would starve waiting for input that the paused pump never
  * delivers.
  *
  * SIGINT is also ignored in the shell's own JVM for the duration of `body`.
  * The child shares the shell's foreground process group (no `setsid` anywhere
  * in the launch chain), so Ctrl-C delivers SIGINT to every process in that
  * group at once: the child dies of its own signal (correct), but the JVM's
  * default SIGINT disposition would kill the shell too without a handler
  * installed here (verified empirically — the shell process exits on Ctrl-C
  * without this bracket). Ignoring the signal for the bracket's duration and
  * restoring the previous handler afterward (the same pattern as sbt's
  * `Signals.withHandler` around `run`/`console`, and git blocking
  * SIGINT/SIGQUIT around the editor/pager) lets Ctrl-C kill only the child; the
  * shell's own prompt handling resumes right after.
  */
object ChildTerminal:

  private val sigint = new Signal("INT")

  def withChild[A](terminal: Terminal)(body: => A): A =
    val attributes = terminal.getAttributes
    val previousIntHandler = Signal.handle(sigint, (_: Signal) => ())
    try body
    finally
      Signal.handle(sigint, previousIntHandler)
      terminal.setAttributes(attributes)
