package orca.backend

/** Tracks the tool-call ids of `ask_user` invocations whose wire echo must be
  * dropped from the conversation event stream.
  *
  * Every stream backend that bridges `ask_user` through the host-side MCP
  * surfaces the question as a [[ConversationEvent.UserQuestion]], so
  * re-emitting the agent's own tool-call block AND the paired tool-result would
  * render the exchange twice — the user's typed answer reappearing as `⎿
  * <answer>` right after the prompt already showed it. Each driver therefore
  * suppresses the `ask_user` tool-call and drops its matching result.
  *
  * What is shared is exactly this id bookkeeping. What is NOT shared — and
  * stays at each call site — is the matcher for "is this an `ask_user` call",
  * because the backends name the tool differently: claude
  * `mcp__<server>__<tool>`, codex a `(server, tool)` pair, gemini
  * `<server>__<tool>` or the bare slug. (Gemini notably must NOT match any name
  * merely *containing* the slug.)
  *
  * Single-threaded: like the rest of the `StreamConversation` subclass state,
  * it is touched only from the reader thread.
  */
private[orca] final class AskUserEchoes:
  private var ids: Set[String] = Set.empty

  /** Remember `id` so the paired tool-result echo is dropped when it arrives.
    */
  def suppress(id: String): Unit = ids = ids + id

  /** True iff `id` was suppressed — and forgets it, since the echo arrives
    * once. Returns false (and does nothing) for an id that was never
    * suppressed, so it is safe to call on every tool-result.
    */
  def consume(id: String): Boolean =
    val hit = ids.contains(id)
    if hit then ids = ids - id
    hit
