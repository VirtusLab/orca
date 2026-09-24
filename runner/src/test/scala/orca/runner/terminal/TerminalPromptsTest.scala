package orca.runner.terminal

import orca.agents.{BackendTag, WireSessionId}
import orca.events.{OrcaListener, Usage}
import orca.backend.{AgentResult, ApprovalDecision, TurnEvent, ObservedTurn}
import orca.testkit.ScriptedTurn

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference

class TerminalPromptsTest extends munit.FunSuite:

  import TerminalPrompts.{PromptOutcome, Prompter}

  private def prompts(
      out: ByteArrayOutputStream,
      prompter: Prompter
  ): TerminalPrompts =
    val ps = new PrintStream(out)
    // `animated = false` makes the output write inline — no ANSI escapes
    // leak into the captured buffer.
    val terminalOutput =
      new TerminalOutputState(ps, useColor = false, animated = false)
    new TerminalPrompts(
      useColor = false,
      output = terminalOutput,
      currentIndent = () => "",
      workDir = None,
      prompter = prompter
    )

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

  private def observed[B <: BackendTag](
      live: ScriptedTurn[B]
  ): ObservedTurn[B] =
    ObservedTurn(live, OrcaListener.noop)

  private def sampleResult: AgentResult[BackendTag.ClaudeCode.type] =
    AgentResult(
      wireId = WireSessionId[BackendTag.ClaudeCode.type]("sid"),
      output = """{"ok":true}""",
      usage = Usage.empty,
      model = None
    )

  test("an approval request truncates a long input with an ellipsis"):
    val buf = new ByteArrayOutputStream()
    val long = "x" * (ToolInputSummary.MaxInlineInputLength + 50)
    val prompter = new ScriptedPrompter(List(PromptOutcome.Answer("yes")))
    val live = new ScriptedTurn(
      List(TurnEvent.ApproveTool("Bash", long, _ => ())),
      Right(sampleResult)
    )
    val _ = prompts(buf, prompter).drive(observed(live))
    val out = buf.toString
    assert(out.contains("…"), s"expected ellipsis; got: $out")
    assert(out.length < long.length + 100)

  test("promptApproval 'y' answer → Allow, prompt text asked"):
    val buf = new ByteArrayOutputStream()
    val answered = new AtomicReference[Option[ApprovalDecision]](None)
    val prompter = new ScriptedPrompter(List(PromptOutcome.Answer("yes")))
    val live = new ScriptedTurn(
      List(
        TurnEvent.ApproveTool(
          "Bash",
          """{"cmd":"ls"}""",
          d => answered.set(Some(d))
        )
      ),
      Right(sampleResult)
    )
    val _ = prompts(buf, prompter = prompter).drive(observed(live))
    assertEquals(answered.get(), Some(ApprovalDecision.Allow))
    assert(prompter.asked.get().exists(_.contains("[y]es")))

  test("promptApproval 'n' answer → Deny"):
    val buf = new ByteArrayOutputStream()
    val answered = new AtomicReference[Option[ApprovalDecision]](None)
    val prompter = new ScriptedPrompter(List(PromptOutcome.Answer("no")))
    val live = new ScriptedTurn(
      List(
        TurnEvent.ApproveTool(
          "Bash",
          """{"cmd":"rm"}""",
          d => answered.set(Some(d))
        )
      ),
      Right(sampleResult)
    )
    val _ = prompts(buf, prompter = prompter).drive(observed(live))
    assertEquals(answered.get(), Some(ApprovalDecision.Deny))

  test("promptApproval interrupted → turn.cancel() called"):
    val buf = new ByteArrayOutputStream()
    val prompter = new ScriptedPrompter(List(PromptOutcome.Interrupted))
    val live = new ScriptedTurn(
      List(
        TurnEvent.ApproveTool("Bash", "{}", _ => ())
      ),
      Right(sampleResult)
    )
    val _ = prompts(buf, prompter = prompter).drive(observed(live))
    assertEquals(
      live.cancelCount.get(),
      1,
      "expected turn.cancel() to fire"
    )

  test("UserQuestion: question rendered, typed reply passed to respond"):
    val buf = new ByteArrayOutputStream()
    val answered = new AtomicReference[Option[String]](None)
    val prompter = new ScriptedPrompter(List(PromptOutcome.Answer("Paris")))
    val live = new ScriptedTurn(
      List(
        TurnEvent.UserQuestion(
          "What's the target deployment region?",
          ans => answered.set(Some(ans))
        )
      ),
      Right(sampleResult)
    )
    val _ = prompts(buf, prompter = prompter).drive(observed(live))
    assertEquals(answered.get(), Some("Paris"))
    assert(
      buf.toString.contains("target deployment region"),
      s"question text missing from output: ${buf.toString}"
    )

  test("UserQuestion interrupted → turn.cancel() called"):
    val buf = new ByteArrayOutputStream()
    val prompter = new ScriptedPrompter(List(PromptOutcome.Interrupted))
    val live = new ScriptedTurn(
      List(
        TurnEvent.UserQuestion("Pick one", _ => ())
      ),
      Right(sampleResult)
    )
    val _ = prompts(buf, prompter = prompter).drive(observed(live))
    assertEquals(
      live.cancelCount.get(),
      1,
      "expected turn.cancel() to fire"
    )
