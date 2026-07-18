package orca.backend

/** Tracks the tool-call ids of `ask_user` invocations whose wire echo must be
  * dropped from the conversation event stream.
  *
  * A bridged `ask_user` question is already surfaced as a
  * [[ConversationEvent.UserQuestion]], so re-emitting the agent's tool-call
  * block and paired tool-result would render the exchange twice. Each driver
  * suppresses the tool-call and drops its matching result.
  *
  * Only this id bookkeeping is shared; the matcher for "is this an `ask_user`
  * call" stays per call site, because backends name the tool differently
  * (claude `mcp__<server>__<tool>`, codex a `(server, tool)` pair, gemini
  * `<server>__<tool>` or the bare slug — and gemini must NOT match a name
  * merely containing the slug).
  *
  * Single-threaded: touched only from the reader thread.
  */
private[orca] final class AskUserEchoes:
  private var ids: Set[String] = Set.empty

  /** Remember `id` so the paired tool-result echo is dropped when it arrives.
    */
  def suppress(id: String): Unit = ids = ids + id

  /** True iff `id` was suppressed, forgetting it (the echo arrives once). False
    * and a no-op for an unsuppressed id, so it is safe to call on every
    * tool-result.
    */
  def consume(id: String): Boolean =
    val hit = ids.contains(id)
    if hit then ids = ids - id
    hit
