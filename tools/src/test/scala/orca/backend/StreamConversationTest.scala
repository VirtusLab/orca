package orca.backend

import orca.llm.BackendTag
import orca.subprocess.FakePipedCliProcess

/** Subclass that "forgets" to call `start()` at the end of its constructor —
  * the exact mistake [[StreamConversation.ensureStarted]] is designed to catch.
  * Public methods should fail loudly rather than silently return "session ended
  * without producing a result".
  */
private class UnstartedConversation(process: FakePipedCliProcess)
    extends StreamConversation[BackendTag.Codex.type](
      process = process,
      backendName = "test"
    ):
  val outputSchema: Option[String] = None
  def sendUserMessage(text: String): Unit = ()
  def canAskUser: Boolean = false
  protected def handleLine(line: String): Unit = ()

class StreamConversationTest extends munit.FunSuite:

  test("awaitResult shouts when the subclass constructor didn't call start"):
    val conv = new UnstartedConversation(new FakePipedCliProcess())
    val ex = intercept[IllegalStateException]:
      val _ = conv.awaitResult()
    assert(
      ex.getMessage.contains("called before start()"),
      s"expected a clear 'called before start()' message; got: ${ex.getMessage}"
    )

  test("events shouts when the subclass constructor didn't call start"):
    val conv = new UnstartedConversation(new FakePipedCliProcess())
    intercept[IllegalStateException]:
      val _ = conv.events.hasNext
