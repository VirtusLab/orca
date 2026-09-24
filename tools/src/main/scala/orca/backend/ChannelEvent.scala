package orca.backend

/** A request from a live turn that the channel ([[Interaction]]) must answer:
  * the backend blocks until `respond` is called. Everything else a turn emits
  * reaches listeners as an `OrcaEvent` ([[ObservedTurn]]).
  */
enum ChannelEvent:
  /** The agent wants to invoke a tool and is asking our permission. The channel
    * must call `respond` exactly once — `Allow` to execute, `Deny` to refuse.
    * The decoder owns the matching request-id bookkeeping; the closure captures
    * it.
    */
  case ApproveTool private[orca] (
      toolName: String,
      rawInput: String,
      respond: ApprovalDecision => Unit
  )

  /** The agent wants a free-form answer from the user. The channel displays
    * `question`, reads a reply, and calls `respond` exactly once with what the
    * user typed; the backend feeds the answer back as a tool result. Only
    * emitted by turns whose `LiveTurn.canAskUser` is true.
    */
  case UserQuestion private[orca] (question: String, respond: String => Unit)

/** Channel's answer to a [[ChannelEvent.ApproveTool]] prompt. */
enum ApprovalDecision:
  case Allow, Deny
