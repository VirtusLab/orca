package orca.backend

/** The autonomous vs interactive choice that drives the SPI's `open`-side
  * branching: whether to wire the MCP `ask_user` tool, whether to enqueue an
  * opening `UserMessage` for a renderer to anchor on, whether the agent's prose
  * is consumed by a drain loop or handed to an `Interaction`. Schema
  * enforcement is *orthogonal* to this — both modes can be structured or
  * free-form — so `outputSchema` stays a separate SPI parameter.
  *
  * `private[orca]` because the SPI exposes the choice through paired methods
  * (`runAutonomous` / `runInteractive`) rather than a `mode: …` argument on
  * user-facing methods. The mode value only travels as far as the
  * backend-internal `openConversation` helper, where the four sites that
  * diverge (MCP server, mcp-config arg, ask_user hint in system prompt,
  * autoApprove entry) can pattern-match on it once.
  */
private[orca] enum ConversationMode:
  case Autonomous
  case Interactive(prompt: String)

  /** The prompt a renderer anchors on, or `""` for autonomous — autonomous
    * calls have no renderer to show it to. Saves the backend call sites that
    * only need this one projection from writing out the full `match`.
    */
  def displayPrompt: String = this match
    case Autonomous          => ""
    case Interactive(prompt) => prompt

  /** True for [[Interactive]] — every call site that branches on the mode only
    * needs this boolean; the prompt itself is read separately via
    * [[displayPrompt]].
    */
  def isInteractive: Boolean = this match
    case Autonomous     => false
    case Interactive(_) => true
