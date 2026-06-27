package orca.runner.terminal

import orca.agents.{BackendTag, SessionId}
import orca.events.{Usage}
import orca.{OrcaInteractiveCancelled}
import orca.backend.{
  ApprovalDecision,
  Conversation,
  ConversationEvent,
  AgentResult
}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference

class ConversationRendererTest extends munit.FunSuite:

  import ConversationRenderer.{PromptOutcome, Prompter}

  private def renderer(
      out: ByteArrayOutputStream,
      showThinking: Boolean = false,
      structuredMode: Boolean = false,
      prompter: Prompter = ScriptedPrompter(Nil)
  ): ConversationRenderer =
    val ps = new PrintStream(out)
    // `animated = false` makes the output write inline — no ANSI escapes
    // leak into the captured buffer.
    val terminalOutput =
      new TerminalOutputState(ps, useColor = false, animated = false)
    val depth = new StageDepth
    new ConversationRenderer(
      useColor = false,
      output = terminalOutput,
      currentIndent = () => depth.contentIndent,
      showThinking = showThinking,
      structuredMode = structuredMode,
      prompter = prompter
    )

  /** A fake Conversation that replays a scripted event list, then returns a
    * scripted outcome from `awaitResult`. The shorthand `outcome` encoding
    * mirrors what real conversations produce:
    *   - `Right(result)` — successful result, returned as `Right(result)`.
    *   - `Left(cancelled: OrcaInteractiveCancelled)` — returned as
    *     `Left(cancelled)` so the caller pattern-matches.
    *   - `Left(other)` — thrown, simulating a fatal subprocess failure.
    */
  private class ScriptedConversation[B <: BackendTag](
      scripted: List[ConversationEvent],
      outcome: Either[Throwable, AgentResult[B]],
      val outputSchema: Option[String] = None
  ) extends Conversation[B]:
    val cancelled = new AtomicReference[Boolean](false)
    def events: Iterator[ConversationEvent] = scripted.iterator
    def awaitResult(): Either[OrcaInteractiveCancelled, AgentResult[B]] =
      outcome match
        case Right(r)                          => Right(r)
        case Left(c: OrcaInteractiveCancelled) => Left(c)
        case Left(t)                           => throw t
    def sendUserMessage(text: String): Unit = ()
    def canAskUser: Boolean = false
    def cancel(): Unit = cancelled.set(true)

  /** Test prompter that replays a scripted list of outcomes and records the
    * prompt strings it was asked for.
    */
  private class ScriptedPrompter(outcomes: List[PromptOutcome])
      extends Prompter:
    private val remaining = new AtomicReference[List[PromptOutcome]](outcomes)
    val asked = new AtomicReference[List[String]](Nil)
    def ask(prompt: String): PromptOutcome =
      val _ = asked.updateAndGet(prompt :: _)
      val next = remaining.getAndUpdate(_.drop(1)).headOption
      next.getOrElse(throw new IllegalStateException("prompter exhausted"))
    def close(): Unit = ()

  private def sampleResult: AgentResult[BackendTag.ClaudeCode.type] =
    AgentResult(
      sessionId = SessionId[BackendTag.ClaudeCode.type]("sid"),
      output = """{"ok":true}""",
      usage = Usage(0L, 0L, None)
    )

  test("non-structured mode: assistant text flushes verbatim at TurnEnd"):
    // Off the structured-output path, the renderer doesn't
    // second-guess the agent's output — JSON-shaped or not, it
    // streams as `●` prose like everything else.
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
      buf.toString.contains("tasks"),
      s"JSON payload should be rendered verbatim; got: ${buf.toString}"
    )

  test(
    "structured mode: assistant text is suppressed (StructuredResult takes over)"
  ):
    // When the conversation was launched with an `outputSchema`,
    // the agent's final text is the structured payload. Suppressing
    // it here lets the listener pick the canonical render via
    // `OrcaEvent.StructuredResult` (raw or summary, never both).
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("""{"tasks":[{"id":1}]}"""),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult)
    )
    val _ = renderer(buf, structuredMode = true).render(conv)
    assert(
      !buf.toString.contains("tasks"),
      s"structured-mode renderer should not render the JSON payload; got: ${buf.toString}"
    )

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

  test("render surfaces awaitResult's Left as-is"):
    val buf = new ByteArrayOutputStream()
    val cancelled = new OrcaInteractiveCancelled()
    val conv = new ScriptedConversation(Nil, Left(cancelled))
    assertEquals(renderer(buf).render(conv), Left(cancelled))

  test("summarise truncates long inputs with an ellipsis"):
    val buf = new ByteArrayOutputStream()
    val long = "x" * (ConversationRenderer.MaxInlineInputLength + 50)
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

  test("UserQuestion: question rendered, typed reply passed to respond"):
    val buf = new ByteArrayOutputStream()
    val answered = new AtomicReference[Option[String]](None)
    val prompter = new ScriptedPrompter(List(PromptOutcome.Answer("Paris")))
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.UserQuestion(
          "What's the target deployment region?",
          ans => answered.set(Some(ans))
        )
      ),
      Right(sampleResult)
    )
    val _ = renderer(buf, prompter = prompter).render(conv)
    assertEquals(answered.get(), Some("Paris"))
    assert(
      buf.toString.contains("target deployment region"),
      s"question text missing from output: ${buf.toString}"
    )

  test("UserQuestion interrupted → conversation.cancel() called"):
    val buf = new ByteArrayOutputStream()
    val prompter = new ScriptedPrompter(List(PromptOutcome.Interrupted))
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.UserQuestion("Pick one", _ => ())
      ),
      Right(sampleResult)
    )
    val _ = renderer(buf, prompter = prompter).render(conv)
    assert(conv.cancelled.get(), "expected conversation.cancel() to fire")
