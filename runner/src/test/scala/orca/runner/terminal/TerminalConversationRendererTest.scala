package orca.runner.terminal

import orca.{
  ApprovalDecision,
  Backend,
  Conversation,
  ConversationEvent,
  LlmResult,
  OrcaInteractiveCancelled,
  SessionId,
  Usage
}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference

class TerminalConversationRendererTest extends munit.FunSuite:

  import TerminalConversationRenderer.{PromptOutcome, Prompter}

  private def renderer(
      out: ByteArrayOutputStream,
      showThinking: Boolean = false,
      prompter: Prompter = ScriptedPrompter(Nil)
  ): TerminalConversationRenderer =
    val ps = new PrintStream(out)
    new TerminalConversationRenderer(
      out = ps,
      useColor = false,
      // `animated = false` makes the bar plain inline writes — no
      // ANSI escapes leak into the captured buffer.
      statusBar = new StatusBar(ps, useColor = false, animated = false),
      depth = new StageDepth,
      showThinking = showThinking,
      prompter = prompter
    )

  /** A fake Conversation that replays a scripted event list, then returns
    * a scripted outcome from `awaitResult`. The shorthand `outcome`
    * encoding mirrors what real conversations produce:
    *   - `Right(result)` — successful result, returned as `Right(result)`.
    *   - `Left(cancelled: OrcaInteractiveCancelled)` — returned as
    *     `Left(cancelled)` so the caller pattern-matches.
    *   - `Left(other)` — thrown, simulating a fatal subprocess failure.
    */
  private class ScriptedConversation[B <: Backend](
      scripted: List[ConversationEvent],
      outcome: Either[Throwable, LlmResult[B]]
  ) extends Conversation[B]:
    val cancelled = new AtomicReference[Boolean](false)
    def events: Iterator[ConversationEvent] = scripted.iterator
    def awaitResult(): Either[OrcaInteractiveCancelled, LlmResult[B]] =
      outcome match
        case Right(r)                          => Right(r)
        case Left(c: OrcaInteractiveCancelled) => Left(c)
        case Left(t)                           => throw t
    def sendUserMessage(text: String): Unit = ()
    def cancel(): Unit = cancelled.set(true)

  /** Test prompter that replays a scripted list of outcomes and records
    * the prompt strings it was asked for.
    */
  private class ScriptedPrompter(outcomes: List[PromptOutcome]) extends Prompter:
    private val remaining = new AtomicReference[List[PromptOutcome]](outcomes)
    val asked = new AtomicReference[List[String]](Nil)
    def ask(prompt: String): PromptOutcome =
      val _ = asked.updateAndGet(prompt :: _)
      val next = remaining.getAndUpdate(_.drop(1)).headOption
      next.getOrElse(throw new IllegalStateException("prompter exhausted"))
    def close(): Unit = ()

  private def sampleResult: LlmResult[Backend.ClaudeCode.type] =
    LlmResult(
      sessionId = SessionId[Backend.ClaudeCode.type]("sid"),
      output = """{"ok":true}""",
      usage = Usage(0L, 0L, None)
    )

  test("AssistantTextDelta streams the text inline without a trailing newline"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantTextDelta("hello ")),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    assert(buf.toString.contains("hello "))

  test("a JSON-only delta + TurnEnd is suppressed (structured payload)"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("""{"tasks":[{"id":1}]}"""),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    assert(
      !buf.toString.contains("tasks"),
      s"JSON-only payload should not be rendered; got: ${buf.toString}"
    )

  test("prose followed by a JSON-only delta still gets rendered as prose"):
    // Buffer + flush is per-turn, so multiple deltas concatenate into
    // one buffer; the JSON wrapper around prose isn't pure JSON, so
    // it flushes.
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("Here is the plan: "),
        ConversationEvent.AssistantTextDelta("""{"tasks":[]}"""),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    val out = buf.toString
    assert(
      out.contains("Here is the plan"),
      s"prose-prefixed turn should still flush; got: $out"
    )

  test("plain prose flushes on TurnEnd"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("just thinking out loud"),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    assert(buf.toString.contains("just thinking out loud"))

  test("AssistantThinkingDelta stays silent when showThinking is false"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantThinkingDelta("inner monologue")),
      Right(sampleResult)
    )
    val _ = renderer(buf, showThinking = false).render(conv)
    assert(
      !buf.toString.contains("inner monologue"),
      s"expected no output; got: ${buf.toString}"
    )

  test("AssistantThinkingDelta renders when showThinking is true"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantThinkingDelta("inner monologue")),
      Right(sampleResult)
    )
    val _ = renderer(buf, showThinking = true).render(conv)
    assert(buf.toString.contains("inner monologue"))

  test("AssistantToolCall renders the name and a summarised input"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantToolCall("Bash", """{"cmd":"ls"}""")),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    val out = buf.toString
    assert(out.contains("Bash"))
    assert(out.contains("{\"cmd\":\"ls\"}"))

  test("ToolResult rendering differs by ok flag"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.ToolResult("Bash", ok = true, "ok-output"),
        ConversationEvent.ToolResult("Bash", ok = false, "failed")
      ),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    val out = buf.toString
    assert(out.contains("ok-output"))
    assert(out.contains("failed"))

  test("Error event renders the message with the ✖ glyph"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(ConversationEvent.Error("boom")),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    assert(buf.toString.contains("boom"))
    assert(buf.toString.contains("✖"))

  test("awaitResult exception propagates through render"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      Nil,
      Left(new OrcaInteractiveCancelled())
    )
    intercept[OrcaInteractiveCancelled](renderer(buf).render(conv))

  test("summarise truncates long inputs with an ellipsis"):
    val buf = new ByteArrayOutputStream()
    val long = "x" * (TerminalConversationRenderer.MaxInlineInputLength + 50)
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantToolCall("Bash", long)),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    val out = buf.toString
    assert(out.contains("…"), s"expected ellipsis; got: $out")
    assert(out.length < long.length + 100)

  test("promptApproval 'y' answer → Allow(None), prompt text asked"):
    val buf = new ByteArrayOutputStream()
    val answered = new AtomicReference[Option[ApprovalDecision]](None)
    val prompter = new ScriptedPrompter(List(PromptOutcome.Answer("yes")))
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.ApproveTool(
          "Bash",
          """{"cmd":"ls"}""",
          d => answered.set(Some(d))
        )
      ),
      Right(sampleResult)
    )
    val _ = renderer(buf, prompter = prompter).render(conv)
    assertEquals(answered.get(), Some(ApprovalDecision.Allow(None)))
    assert(prompter.asked.get().exists(_.contains("[y]es")))

  test("promptApproval 'n' answer → Deny with reason"):
    val buf = new ByteArrayOutputStream()
    val answered = new AtomicReference[Option[ApprovalDecision]](None)
    val prompter = new ScriptedPrompter(List(PromptOutcome.Answer("no")))
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.ApproveTool(
          "Bash",
          """{"cmd":"rm"}""",
          d => answered.set(Some(d))
        )
      ),
      Right(sampleResult)
    )
    val _ = renderer(buf, prompter = prompter).render(conv)
    answered.get() match
      case Some(ApprovalDecision.Deny(Some(reason))) =>
        assert(reason.contains("user denied"))
      case other => fail(s"expected Deny(Some(...)), got $other")

  test("promptApproval interrupted → conversation.cancel() called"):
    val buf = new ByteArrayOutputStream()
    val prompter = new ScriptedPrompter(List(PromptOutcome.Interrupted))
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.ApproveTool("Bash", "{}", _ => ())
      ),
      Right(sampleResult)
    )
    val _ = renderer(buf, prompter = prompter).render(conv)
    assert(conv.cancelled.get(), "expected conversation.cancel() to fire")
