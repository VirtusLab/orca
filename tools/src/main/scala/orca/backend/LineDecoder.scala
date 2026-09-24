package orca.backend

import orca.agents.BackendTag
import orca.events.TurnDebit

/** One backend's wire protocol, as a fold over the lines of its stream: each
  * line turns the decoder's state `S` into a [[Step]]. [[DecodedTurn]] runs the
  * fold on its reader fork and owns everything else — message grammar, stderr,
  * outcome, teardown — calling back [[onUnsettledEnd]] for a turn that ended
  * without a settle.
  *
  * `line` may write to the wire (a stdin reply, an HTTP post) but keeps no
  * state outside `S`. A throw from it is reported as a parse-error `Error`
  * event and leaves the state unchanged.
  */
private[orca] trait LineDecoder[B <: BackendTag, S]:

  /** The user-facing backend name, prefixed to stderr lines and failures. */
  def backendName: String

  /** The protocol message a stream that ended cleanly is diagnosed as missing —
    * `"a result message"`, `"a turn.completed event"`, …
    */
  def terminalMessageNoun: String

  def init: S

  def line(state: S, line: String): Step[B, S]

  /** What the turn had spent by `state`, for a turn that ends badly: a read
    * error, a bad exit, a cancel, or a failure the decoder settles itself.
    * [[TurnDebit.Unobserved]] claims the protocol reports nothing to accrue.
    */
  def failedTurnDebit(state: S): TurnDebit

  /** Protocol-level context folded into failure messages ahead of stderr. */
  def protocolContext(state: S): Option[String] = None

  /** Runs once when the stream ended without a settle — a cancel, a crash, a
    * dropped connection — so a backend whose turn runs on a shared server can
    * stop it there.
    */
  def onUnsettledEnd(): Unit = ()

  /** Known-benign stderr lines, dropped instead of surfacing as an `Error`.
    * Sees the line stripped of terminal control sequences and trimmed.
    */
  def isStderrNoise(line: String): Boolean = false

/** The result of decoding one line. */
private[orca] enum Step[B <: BackendTag, S]:
  case Continue(state: S, events: List[TurnEvent])

  /** The turn's outcome is known: `events` are the last ones it emits, and any
    * later line is ignored. `state` still feeds the failure diagnostics.
    */
  case Settle(state: S, events: List[TurnEvent], outcome: Settled[B])

private[orca] object Step:
  def continue[B <: BackendTag, S](
      state: S,
      events: TurnEvent*
  ): Step[B, S] = Step.Continue(state, events.toList)

/** How a decoder ends a turn. A `Failed` turn surfaces as
  * [[orca.AgentTurnFailed]] with `message` plus the diagnostics the
  * [[DecodedTurn]] collected.
  */
private[orca] enum Settled[B <: BackendTag]:
  case Succeeded(result: AgentResult[B])
  case Failed(message: String, debit: TurnDebit)
