package orca

/** One live interactive session with a backend. Owned by the driver, read
  * and written to by the channel via [[Interaction.drive]].
  *
  * `events` is a single-consumer blocking iterator that yields every
  * [[ConversationEvent]] produced by the subprocess in order. The iterator
  * terminates when the session ends — either cleanly (call `awaitResult`
  * to retrieve the outcome) or via cancellation (`awaitResult` then
  * throws [[OrcaInteractiveCancelled]]).
  *
  * Tool-approval decisions are delivered via the closure carried on
  * [[ConversationEvent.ApproveTool]] — the channel does not track
  * request-ids. `sendUserMessage` injects an unsolicited user turn; it
  * and `cancel` are safe to call from any thread.
  */
trait Conversation[B <: Backend]:

  /** Events from the subprocess, in arrival order. Blocks on `next()`
    * until a line has been parsed or the session ends. `hasNext` returns
    * false once the terminal event has been consumed.
    */
  def events: Iterator[ConversationEvent]

  /** Block until the session finishes, then return its final outcome.
    * Throws [[OrcaInteractiveCancelled]] if the session was cancelled
    * (either via `cancel()` or because the subprocess died abnormally).
    * Other failures propagate as [[OrcaFlowException]].
    */
  def awaitResult(): LlmResult[B]

  /** Inject a user turn mid-conversation. */
  def sendUserMessage(text: String): Unit

  /** Cancel the current session. The driver tears down the subprocess,
    * closes the events iterator, and `awaitResult()` then throws
    * [[OrcaInteractiveCancelled]]. Calling `cancel` twice is a no-op.
    */
  def cancel(): Unit
