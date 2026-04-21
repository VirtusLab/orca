package orca

enum OrcaEvent:
  case StageStarted(name: String)
  case StageCompleted(name: String, result: String)
  case LlmOutput(text: String)
  case ToolUse(tool: String, args: String)
  case TokensUsed(usage: Usage)
  case Error(message: String)

trait OrcaListener:
  def onEvent(event: OrcaEvent): Unit
