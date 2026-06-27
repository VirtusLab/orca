package orca.backend

import orca.agents.BackendTag
import orca.subprocess.FakePipedCliProcess

/** Subclass that "forgets" to call `start()` at the end of its constructor —
  * the exact mistake [[StreamConversation.ensureStarted]] is designed to catch.
  * Public methods should fail loudly rather than silently return "session ended
  * without producing a result".
  */
private class UnstartedConversation(process: FakePipedCliProcess)
    extends StreamConversation[BackendTag.Codex.type](
      source = StreamSource.fromProcess(process),
      backendName = "test"
    ):
  val outputSchema: Option[String] = None
  def sendUserMessage(text: String): Unit = ()
  protected def handleLine(line: String): Unit = ()

class StreamConversationTest extends munit.FunSuite:

  // Each public entry point must report which one was called so a future
  // change that skips the guard on one of them surfaces in the message,
  // not just in a generic IllegalStateException.
  private val guardedEntryPoints: List[(String, Conversation[?] => Unit)] =
    List(
      "awaitResult" -> { c =>
        val _ = c.awaitResult()
      },
      "events" -> { c =>
        val _ = c.events.hasNext
      }
    )

  for (label, action) <- guardedEntryPoints do
    test(s"$label shouts when the subclass constructor didn't call start"):
      val conv = new UnstartedConversation(new FakePipedCliProcess())
      val ex = intercept[IllegalStateException](action(conv))
      assert(
        ex.getMessage.contains(s"$label called before start()"),
        s"expected the message to name `$label` as the entry point; " +
          s"got: ${ex.getMessage}"
      )
