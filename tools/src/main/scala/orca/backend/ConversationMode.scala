package orca.backend

/** The autonomous vs interactive choice that drives the SPI's `open`-side
  * branching: whether to wire the MCP `ask_user` tool, whether to enqueue an
  * opening `UserMessage`, whether the agent's prose is drained or handed to an
  * `Interaction`. Schema enforcement is orthogonal — both modes can be
  * structured or free-form — so `outputSchema` stays a separate SPI parameter.
  *
  * Internal: the mode travels only as far as the backend-internal
  * `openConversation` helper, where the four diverging sites (MCP server,
  * mcp-config arg, ask_user hint in system prompt, autoApprove entry)
  * pattern-match on it once.
  */
private[orca] enum ConversationMode:
  case Autonomous
  case Interactive(prompt: String)

  /** The prompt a renderer anchors on, or `""` for autonomous (no renderer to
    * show it to).
    */
  def displayPrompt: String = this match
    case Autonomous          => ""
    case Interactive(prompt) => prompt

  /** True for [[Interactive]]. */
  def isInteractive: Boolean = this match
    case Autonomous     => false
    case Interactive(_) => true
