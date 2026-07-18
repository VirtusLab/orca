package orca.backend

import orca.agents.{BackendTag}
import orca.{OrcaInteractiveCancelled}

import ox.Ox

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
  * `cancel` is safe to call from any thread.
  */
trait Conversation[B <: BackendTag]:

  /** The JSON-schema string the conversation was launched with, or `None` for
    * free-form prose. Renderers and channels consult it to decide whether the
    * agent's final assistant text is the structured payload (noise to suppress
    * in favour of an `OrcaEvent.StructuredResult`) or genuine prose to flush.
    */
  def outputSchema: Option[String]

  /** Events from the subprocess, in arrival order. Blocks on `next()` until a
    * line has been parsed or the session ends; `hasNext` returns false once the
    * terminal event has been consumed.
    *
    * Takes `using Ox`: a stream-driven driver forks its background workers into
    * the caller's per-turn scope on first touch of the surface.
    */
  def events(using Ox): Iterator[ConversationEvent]

  /** Block until the session finishes, then return its outcome.
    *
    *   - `Right(result)` — the session produced an [[AgentResult]] cleanly.
    *   - `Left(cancelled)` — [[cancel]] was called, or the subprocess died in a
    *     way the driver classified as a cancellation. Recoverable: the caller
    *     can render a "cancelled" message, fail the stage, or propagate.
    *
    * Genuine subprocess failures (parse errors, the agent reporting `is_error`,
    * abnormal exit codes) keep throwing [[OrcaFlowException]] — non-recoverable
    * "the backend is broken" cases.
    *
    * Takes `using Ox` for the same reason as [[events]].
    */
  def awaitResult()(using Ox): Either[OrcaInteractiveCancelled, AgentResult[B]]

  /** Whether the agent can pause to ask the host user a clarifying question and
    * have the answer routed back into its turn. When `true`, the driver emits
    * [[ConversationEvent.UserQuestion]] events whose `respond` closure delivers
    * the typed answer to the blocked agent. True for interactive claude and
    * codex sessions (both via the shared `AskUserMcpServer`); false for
    * autonomous sessions and backends that don't wire the bridge.
    */
  def canAskUser: Boolean

  /** Cancel the current session. The driver tears down the subprocess and
    * closes the events iterator; `awaitResult()` then returns a
    * `Left(OrcaInteractiveCancelled)`. Calling `cancel` twice is a no-op.
    */
  def cancel(): Unit
