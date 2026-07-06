package orca.runner.terminal

import orca.runner.terminal.ConversationRenderer.{PromptOutcome, Prompter}
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger

class TerminalInteractionTest extends munit.FunSuite:

  test(
    "default output stream encodes UTF-8 regardless of the JVM launch locale"
  ):
    // Regression: under a non-UTF-8 locale (C/POSIX — common in containers and
    // editor sandboxes) the JVM resolves System.err to US-ASCII and every glyph
    // (…, ✖, ▸, ●) encodes to '?'. The default stream must force UTF-8.
    assertEquals(TerminalInteraction.utf8Stderr.charset(), UTF_8)

  test("close() closes the interaction's prompter exactly once"):
    // The prompter is process-scoped: renderers never close it, so the
    // interaction owns its teardown — closed once, at close(), not per
    // conversation.
    val closes = new AtomicInteger(0)
    val prompter = new Prompter:
      def ask(prompt: String): PromptOutcome = PromptOutcome.Interrupted
      override def close(): Unit =
        val _ = closes.incrementAndGet()
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false,
        prompter = prompter
      )
      interaction.close()
    assertEquals(closes.get(), 1)
