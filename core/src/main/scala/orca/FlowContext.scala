package orca

trait FlowContext:
  val claude: ClaudeTool
  val codex: CodexTool
  val git: GitTool
  val gh: GitHubTool
  val fs: FsTool
  val userPrompt: String
  def emit(event: OrcaEvent): Unit

class OrcaFlowException(message: String) extends RuntimeException(message)
