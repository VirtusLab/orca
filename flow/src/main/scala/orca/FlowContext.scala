package orca

// TODO: user-facing type - missing docs
trait FlowContext:
  def claude: ClaudeTool
  def codex: CodexTool
  def git: GitTool
  def gh: GitHubTool
  def fs: FsTool
  def userPrompt: String
  def emit(event: OrcaEvent): Unit
