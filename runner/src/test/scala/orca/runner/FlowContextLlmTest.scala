package orca.runner

import orca.{FlowContext, OrcaArgs, runFlow}
import orca.llm.LlmTool
import orca.runner.terminal.TerminalInteraction
import orca.tools.opencode.OpencodeLauncher
import orca.llm.DefaultPrompts
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

/** Verifies that the `leadModel` selector passed to `runFlow` is resolved
  * against the flow context and surfaced as `summon[FlowContext].llm`.
  */
class FlowContextLlmTest extends munit.FunSuite:

  test("FlowContext.llm resolves the leadModel selector passed to runFlow"):
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
        leadModel = _ => StubLlm.claude,
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = Nil,
        branchNaming = None,
        returnToStartBranch = false,
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
