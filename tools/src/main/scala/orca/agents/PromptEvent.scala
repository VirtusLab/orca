package orca.agents

import orca.events.{OrcaEvent, OrcaListener}

/** Whether a turn fires `OrcaEvent.UserPrompt` for its prompt. `Suppress` hides
  * the prompt from every listener (display and log alike); the turn's other
  * events fire either way.
  */
enum PromptEvent:
  case Emit, Suppress

  private[orca] def fire(events: OrcaListener, prompt: String): Unit =
    this match
      case Emit     => events.onEvent(OrcaEvent.UserPrompt(prompt))
      case Suppress => ()
