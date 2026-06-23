package orca.runner

import orca.{FlowContext, OrcaArgs, runFlow}
import orca.llm.LlmTool
import orca.runner.terminal.TerminalInteraction
import orca.tools.opencode.OpencodeLauncher
import orca.llm.DefaultPrompts
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

/** Verifies that after `runFlow` is called with a given leading model, the body
  * can retrieve that same model via `summon[FlowContext].llm`.
  */
class FlowContextLlmTest extends munit.FunSuite:

  test("FlowContext.llm returns the leading model passed to runFlow"):
    val workDir = TempRepo.create()
    var seen: Option[LlmTool[?]] = None
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      runFlow(
        args = OrcaArgs("test-llm"),
        llm = StubLlm.claude,
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = Nil,
        branchNaming = None,
        progressStore = None,
        claude = None,
        opencode = None,
        opencodeLauncher = OpencodeLauncher.default,
        pi = None,
        git = None,
        gh = None,
        fs = None,
        prompts = DefaultPrompts
      ):
        seen = Some(summon[FlowContext].llm)
    assert(
      seen.exists(_ eq StubLlm.claude),
      s"expected FlowContext.llm to be StubLlm.claude but got: $seen"
    )

end FlowContextLlmTest
