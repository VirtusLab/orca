package orca.backend

import orca.agents.StructuredOutputMode
import orca.backend.mcp.AskUserSession

/** The per-turn settings a [[StreamConversation]] runs with, beside its source
  * and decoder.
  *
  * @param openingPrompt
  *   surfaced as a `UserMessage` before any agent output (interactive turns)
  * @param onUnsettledEnd
  *   runs once the stream has ended without the decoder settling — a cancel, a
  *   crash, a dropped connection — so a backend whose turn runs on a shared
  *   server can stop it there
  */
private[orca] final case class ConversationSpec(
    openingPrompt: Option[String],
    outputSchema: Option[String],
    structuredOutputMode: StructuredOutputMode,
    askUser: AskUserChannel,
    onUnsettledEnd: () => Unit
)

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
