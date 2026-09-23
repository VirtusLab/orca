package orca.backend

/** The autonomous vs interactive choice that drives [[AgentBackend.open]]'s
  * branching: whether to wire the MCP `ask_user` tool and whether to enqueue an
  * opening `UserMessage`. Schema enforcement is orthogonal — both modes can be
  * structured or free-form — so `outputSchema` stays a separate [[TurnRequest]]
  * field.
  */
private[orca] enum ConversationMode:
  case Autonomous
  case Interactive(prompt: String)

  /** The prompt surfaced as the turn's `UserPrompt`; `None` for autonomous,
    * whose caller emits it.
    */
  def openingPrompt: Option[String] = this match
    case Autonomous          => None
    case Interactive(prompt) => Some(prompt)

  /** True for [[Interactive]]. */
  def isInteractive: Boolean = this match
    case Autonomous     => false
    case Interactive(_) => true
