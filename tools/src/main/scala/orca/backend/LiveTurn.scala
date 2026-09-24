package orca.backend

import orca.agents.{BackendTag, StructuredOutputMode}
import orca.{OrcaInteractiveCancelled}

/** One turn in progress on a backend: its events as they arrive, its outcome,
  * and a way to cancel it. [[AgentBackend.open]] returns it; the channel reads
  * and answers it via [[Interaction.drive]]. Not a conversation — that is the
  * history the backend keeps across turns (see [[SessionSupport]]).
  *
  * `events` is a single-consumer blocking iterator that yields every
  * [[TurnEvent]] produced by the subprocess in order. The iterator terminates
  * when the turn ends; the final outcome is read via `awaitResult`.
  *
  * Tool-approval decisions are delivered via the closure carried on
  * [[TurnEvent.ApproveTool]] — the channel does not track request-ids. `cancel`
  * is safe to call from any thread.
  */
private[orca] trait LiveTurn[B <: BackendTag]:

  /** The JSON-schema string the turn was launched with, or `None` for free-form
    * prose. Renderers and channels consult it to decide whether the agent's
    * final assistant text is the structured payload (noise to suppress in
    * favour of an `OrcaEvent.StructuredResult`) or genuine prose to flush.
    */
  def outputSchema: Option[String]

  /** Turn-side view of [[AgentBackend.structuredOutputMode]], read only when
    * [[outputSchema]] is defined.
    *
    * Defaults to `RawText`, the withholding shape: a `Tool` backend that
    * forgets to declare loses its closing message's prose, whereas the reverse
    * default would leak the JSON payload. A decorator must forward what it
    * wraps: leaving the default there silently changes the wrapped backend's
    * delivery.
    */
  def structuredOutputMode: StructuredOutputMode = StructuredOutputMode.RawText

  /** Events from the subprocess, in arrival order. Blocks on `next()` until a
    * line has been parsed or the turn ends; `hasNext` returns false once the
    * terminal event has been consumed.
    */
  def events: Iterator[TurnEvent]

  /** Block until the turn finishes, then return its outcome.
    *
    *   - `Right(result)` — the turn produced an [[AgentResult]] cleanly.
    *   - `Left(cancelled)` — [[cancel]] was called (a read that throws because
    *     of the cancel's kill still counts). Recoverable: the caller can render
    *     a "cancelled" message, fail the stage, or propagate.
    *
    * Genuine subprocess failures (parse errors, the agent reporting `is_error`,
    * abnormal exit codes) keep throwing [[OrcaFlowException]] — non-recoverable
    * "the backend is broken" cases.
    */
  def awaitResult(): Either[OrcaInteractiveCancelled, AgentResult[B]]

  /** Whether the agent can pause to ask the host user a clarifying question and
    * have the answer routed back into its turn. When `true`, the turn emits
    * [[TurnEvent.UserQuestion]] events whose `respond` closure delivers the
    * typed answer to the blocked agent. True for interactive claude and codex
    * turns (both via the shared `AskUserMcpServer`); false for autonomous turns
    * and backends that don't wire the bridge.
    */
  def canAskUser: Boolean

  /** Cancel the turn: tear down the subprocess and close the events iterator;
    * `awaitResult()` then returns a `Left(OrcaInteractiveCancelled)`. Blocks
    * until the turn has stopped; safe to call more than once.
    */
  def cancel(): Unit
