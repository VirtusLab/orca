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
private[orca] enum SessionMode:
  case Autonomous
  case Interactive(displayPrompt: String)
