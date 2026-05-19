package orca.backend

import orca.llm.{BackendTag}
import orca.{OrcaInteractiveCancelled}

/** One live interactive session with a backend. Owned by the driver, read and
  * written to by the channel via [[Interaction.drive]].
  *
  * `events` is a single-consumer blocking iterator that yields every
  * [[ConversationEvent]] produced by the subprocess in order. The iterator
  * terminates when the session ends; the final outcome is read via
  * `awaitResult`.
  *
  * Tool-approval decisions are delivered via the closure carried on
  * [[ConversationEvent.ApproveTool]] — the channel does not track request-ids.
  * `sendUserMessage` injects an unsolicited user turn; it and `cancel` are safe
  * to call from any thread.
  */
trait Conversation[B <: BackendTag]:

  /** The JSON-schema string the conversation was launched with, when the caller
    * asked for a structured payload via `claude.resultAs[O].interactive(...)`
    * or similar. `None` means the conversation is free-form prose.
    *
    * Renderers and channels can consult this to decide whether the agent's
    * final assistant text is the structured payload (and therefore noise to
    * suppress in favour of an `OrcaEvent.StructuredResult` event), or genuine
    * prose to flush verbatim.
    */
  def outputSchema: Option[String]

  /** Events from the subprocess, in arrival order. Blocks on `next()` until a
    * line has been parsed or the session ends. `hasNext` returns false once the
    * terminal event has been consumed.
    */
  def events: Iterator[ConversationEvent]

  /** Block until the session finishes, then return its outcome.
    *
    *   - `Right(result)` — the session produced an [[LlmResult]] cleanly.
    *   - `Left(cancelled)` — the user (or some peer) called [[cancel]], or the
    *     subprocess died in a way the driver classified as a cancellation.
    *     Recoverable: the caller can render a "cancelled" message, fail the
    *     stage, or propagate.
    *
    * Genuine subprocess failures (parse errors, the agent reporting `is_error`,
    * abnormal exit codes) keep throwing [[OrcaFlowException]] — those aren't
    * recoverable signals; they're "the backend is broken, panic" cases.
    */
  def awaitResult(): Either[OrcaInteractiveCancelled, LlmResult[B]]

  /** Inject a user turn mid-conversation. */
  def sendUserMessage(text: String): Unit

  /** Cancel the current session. The driver tears down the subprocess and
    * closes the events iterator; `awaitResult()` then returns a
    * `Left(OrcaInteractiveCancelled)`. Calling `cancel` twice is a no-op.
    */
  def cancel(): Unit
