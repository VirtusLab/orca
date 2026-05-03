package orca

/** Minimal FlowContext stub for unit-testing stage/fail and other helpers that
  * only touch `emit` + `userPrompt`. Tool accessors are lazy so merely
  * constructing the context doesn't throw; tests that exercise them should
  * provide real (or stubbed) implementations.
  */
class TestFlowContext(
    dispatcher: EventDispatcher,
    val userPrompt: String = ""
) extends FlowContext:
  private def stub(name: String) =
    throw new NotImplementedError(s"$name is not wired in TestFlowContext")

  lazy val claude: ClaudeTool = stub("claude")
  lazy val codex: CodexTool = stub("codex")
  lazy val git: GitTool = stub("git")
  lazy val gh: GitHubTool = stub("gh")
  lazy val fs: FsTool = stub("fs")

  def emit(event: OrcaEvent): Unit = dispatcher.onEvent(event)
