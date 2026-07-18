package orca.shell.run

import org.jline.terminal.Attributes
import org.jline.terminal.TerminalBuilder
import sun.misc.{Signal, SignalHandler}

import java.util.concurrent.atomic.AtomicInteger

class ChildTerminalTest extends munit.FunSuite:

  test("withChild restores attributes mutated by body, even when body throws"):
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try
      val before = terminal.getAttributes.toString
      val flippedEcho = !terminal.getAttributes.getLocalFlag(Attributes.LocalFlag.ECHO)
      val _ = intercept[RuntimeException]:
        ChildTerminal.withChild(terminal):
          val mutated = terminal.getAttributes
          mutated.setLocalFlag(Attributes.LocalFlag.ECHO, flippedEcho)
          terminal.setAttributes(mutated)
          throw new RuntimeException("child crashed in raw mode")
      assertEquals(terminal.getAttributes.toString, before)
    finally terminal.close()

  // Ctrl-C reaches every process in the shared foreground group, so without a
  // handler here the JVM's default SIGINT disposition would kill the shell
  // along with the child. Reproduces that mechanism in-process: install a
  // counting handler, confirm it's not invoked (SIGINT is ignored) while
  // `withChild`'s body is running, then confirm it fires again once the
  // bracket has exited.
  //
  // `Signal.raise` only requests delivery — the JVM's signal-dispatcher
  // thread invokes the registered `SignalHandler` asynchronously, so
  // `awaitCount` polls instead of asserting immediately after `raise`.
  test("withChild ignores SIGINT while body runs, restoring the previous handler after"):
    val terminal = TerminalBuilder.builder().dumb(true).build()
    val signal = new Signal("INT")
    val handlerCalls = new AtomicInteger(0)
    val countingHandler: SignalHandler = _ => handlerCalls.incrementAndGet(): Unit
    val original = Signal.handle(signal, countingHandler)
    try
      ChildTerminal.withChild(terminal):
        Signal.raise(signal)
        // Grace period: if the ignore handler weren't installed, the
        // dispatcher thread would have run countingHandler well within this.
        Thread.sleep(200)
      assertEquals(handlerCalls.get(), 0, "SIGINT must be ignored for the duration of the child bracket")
      Signal.raise(signal)
      awaitCount(handlerCalls, 1, "the pre-existing handler must be reinstalled once the child exits")
    finally
      Signal.handle(signal, original)
      terminal.close()

  private def awaitCount(counter: AtomicInteger, expected: Int, clue: String): Unit =
    val deadlineNanos = System.nanoTime() + 2_000_000_000L
    while counter.get() != expected && System.nanoTime() < deadlineNanos do Thread.sleep(5)
    assertEquals(counter.get(), expected, clue)
