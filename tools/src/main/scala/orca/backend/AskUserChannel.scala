package orca.backend

import orca.backend.mcp.AskUserSession

/** How a turn's agent reaches the user with a question. */
private[orca] enum AskUserChannel:
  case Unavailable

  /** Through orca's `ask_user` MCP server (claude, codex, gemini). */
  case Mcp(session: AskUserSession)

  /** Through the backend's own protocol (opencode's `question` event, pi's
    * extension UI requests), which its decoder translates.
    */
  case Native

  def isAvailable: Boolean = this match
    case Unavailable     => false
    case Mcp(_) | Native => true
