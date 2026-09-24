package orca.backend

/** The per-turn settings a [[DecodedTurn]] runs with, beside its source and
  * decoder.
  *
  * @param openingPrompt
  *   surfaced as a `UserMessage` before any agent output (interactive turns)
  */
private[orca] final case class DecodedTurnSpec(
    openingPrompt: Option[String],
    outputSchema: Option[String],
    askUser: AskUserChannel
)
