package orca.backend

import orca.agents.StructuredOutputMode

/** The per-turn settings a [[StreamConversation]] runs with, beside its source
  * and decoder.
  *
  * @param openingPrompt
  *   surfaced as a `UserMessage` before any agent output (interactive turns)
  */
private[orca] final case class ConversationSpec(
    openingPrompt: Option[String],
    outputSchema: Option[String],
    structuredOutputMode: StructuredOutputMode,
    askUser: AskUserChannel
)
