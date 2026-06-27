package orca.backend

import orca.events.Usage
import orca.agents.{BackendTag, SessionId}

/** Drives the [[StreamConversation]] base class from a non-subprocess
  * [[StreamSource]] — the property the OpenCode backend relies on (its source
  * is an SSE connection, not a `PipedCliProcess`).
  */
class StreamSourceConversationTest extends munit.FunSuite:

  private class ListSource(items: List[String]) extends StreamSource:
    def lines: Iterator[String] = items.iterator
    def errorLines: Iterator[String] = Iterator.empty
    def interrupt(): Unit = ()
    def tryExitCode: Option[Int] = Some(0)

  /** Lines become text deltas; the `DONE` line settles the result. */
  private class TestConversation(source: StreamSource)
      extends StreamConversation[BackendTag.Codex.type](source, "test"):
    import StreamConversation.Outcome
    val outputSchema: Option[String] = None
    def sendUserMessage(text: String): Unit = ()
    protected def handleLine(line: String): Unit =
      if line == "DONE" then
        val result = AgentResult(
          SessionId[BackendTag.Codex.type]("s1"),
          output = "hello",
          usage = Usage(0L, 0L, None)
        )
        val _ = outcomeRef.compareAndSet(None, Some(Outcome.Success(result)))
      else eventQueue.enqueue(ConversationEvent.AssistantTextDelta(line))
    start()

  test("translates lines to events and settles the result"):
    val conv = new TestConversation(new ListSource(List("a", "b", "DONE")))
    assertEquals(
      conv.events.toList,
      List(
        ConversationEvent.AssistantTextDelta("a"),
        ConversationEvent.AssistantTextDelta("b")
      )
    )
    assertEquals(conv.awaitResult().map(_.output), Right("hello"))

  test("a source that ends without a result fails the turn"):
    val conv = new TestConversation(new ListSource(List("a")))
    conv.events.foreach(_ => ())
    intercept[orca.AgentTurnFailed](conv.awaitResult())
