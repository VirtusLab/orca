package orca.backend

import java.util.concurrent.atomic.AtomicBoolean

import orca.events.OrcaListener
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  SessionId,
  StructuredOutputMode,
  ToolSet
}

import ox.Ox

/** SPI implemented per backend (Claude, Codex, …), called from the
  * autonomous-text and structured-output paths ([[AutonomousTextCall]],
  * [[AgentCall]]).
  *
  * Each method takes a `session: SessionId[B]` — the same value across calls;
  * the backend decides internally whether this is a first invocation (session
  * needs creating) or a continuation. `runAutonomous` runs to completion
  * off-screen and returns the result; `runInteractive` returns a live
  * [[Conversation]] the caller drives through an [[Interaction]].
  *
  * `prompt` is the full wire-level message sent to the agent, with all template
  * scaffolding, schema, and rules already wrapped around the user's input.
  * `displayPrompt` (interactive only) is what the renderer shows the user.
  */
trait AgentBackend[B <: BackendTag](
    /** Backing store for [[isClosed]]/[[markClosed]]. Defaults to a fresh,
      * unshared flag, correct for every backend whose builders go through
      * `BaseAgent.copyTool` and stay on the SAME backend instance. A builder
      * that instead constructs a SIBLING backend (today only claude's
      * [[orca.tools.claude.ClaudeBackend.withNetworkTools]]) MUST pass the
      * parent's `closedFlag` here, so `markClosed()` on either instance is
      * visible through both — otherwise a handle derived via that builder and
      * leaked past flow-end bypasses the use-after-close guard entirely.
      */
    private[orca] val closedFlag: AtomicBoolean = new AtomicBoolean(false)
):
  /** Run one autonomous turn against `session` and return its result.
    *
    * `events` receives per-tool-use and per-message progress as the subprocess
    * runs. Defaults to a no-op listener for callers (typically tests) that
    * don't observe progress.
    *
    * `outputSchema`, when supplied, is the JSON Schema the final assistant
    * payload must conform to. Backends that enforce schemas natively (claude's
    * `--json-schema`) pass it to the CLI; others can ignore it. Either way the
    * schema is forwarded to the conversation so the drain can recognise "the
    * agent's last message IS the structured payload" and suppress the raw JSON
    * from the user log — the caller surfaces it via
    * `OrcaEvent.StructuredResult` instead.
    */
  def runAutonomous(
      prompt: String,
      session: SessionId[B],
      config: AgentConfig,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): AgentResult[B]

  /** Launch an interactive session against `session` and return a live
    * [[Conversation]] the caller hands to [[Interaction.drive]]. The backend
    * owns the subprocess and event parsing; the channel owns UX.
    *
    * `outputSchema` is the JSON Schema the agent's final reply must conform to,
    * or `None` for free-form text. Backends that support structured-output
    * validation (claude's `--json-schema`) enforce it; others ignore it and let
    * the caller validate post-hoc.
    */
  def runInteractive(
      prompt: String,
      session: SessionId[B],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[B]

  /** The working directory the agent subprocess sees, fixed for this backend's
    * whole lifetime — every spawn and every session-existence probe runs
    * against this same path.
    */
  def workDir: os.Path

  /** This backend's whole session capability as one structural value: the id
    * scheme plus, for durable backends, the existence probe (see
    * [[SessionSupport.durable]] / [[SessionSupport.ephemeral]]). The framework
    * reaches sessions exclusively through this, so a backend cannot half-wire
    * resume by providing persist/probe/register piecemeal.
    */
  def sessions: SessionSupport[B]

  /** Runtime value of the compile-time tag `B`; lets the runtime record which
    * backend a session belongs to.
    */
  def tag: B

  /** How strongly THIS backend enforces the restriction a `(tools,
    * autoApprove)` combination requests — a pure classification of the flags
    * this backend's `*Args` would build. The mapping differs materially across
    * backends (a `Full` + `AutoApprove.Only` is a mechanical allowlist on
    * claude but a whole-sandbox approximation on codex and unencoded on the
    * rest), so it is surfaced as data. The complete matrix is machine-checked
    * in `runner/src/test/scala/orca/runner/EnforcementTableTest.scala`; each
    * backend delegates to its `*Args.enforcement`, where the per-cell rationale
    * lives.
    *
    * Abstract, not defaulted to `Enforcement.Ignored`, so a new backend cannot
    * ship without answering this. Real backends implement it and add their rows
    * to `EnforcementTableTest`; test doubles that never call `enforcement` add
    * a one-line `Enforcement.Ignored` override.
    */
  def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement

  /** How THIS backend's wire delivers a structured (`resultAs[O]`) payload
    * ([[orca.agents.StructuredOutputMode]]). Prompt assembly
    * ([[orca.agents.Prompts.autonomous]]) branches on it, so a backend that
    * misdeclares gets an instruction that contradicts its wire and steers weak
    * models into malformed replies.
    *
    * Abstract, not defaulted, for the same reason as [[enforcement]]. Real
    * backends declare what their CLI actually does; test doubles that never
    * assemble prompts add a one-line `RawText` override.
    */
  def structuredOutputMode: StructuredOutputMode

  /** Release background resources this backend owns (processes, servers, drain
    * forks). Called by the runtime in the flow body's `finally`, BEFORE the
    * flow scope joins its forks — a resource whose teardown unblocks a
    * non-interruptible read must happen here, not in a `releaseAfterScope`
    * finalizer (Ox runs those after the join). Idempotent; default no-op.
    */
  def close(): Unit = ()

  // The use-after-close latch lives on the backend, not the Agent instance:
  // every builder goes through `BaseAgent.copyTool`, which constructs a new
  // agent sharing this same backend — a per-agent flag would reset to "open"
  // on every derived handle, letting a leaked handle bypass the guard.

  /** Latch this backend as closed — its owning flow has ended, and every run
    * entry point gated on [[isClosed]] must refuse from now on. Called by
    * `BaseAgent.close()` before [[close]]; separate from it so a subclass
    * overriding `close()` for resource teardown cannot forget the latch.
    */
  private[orca] final def markClosed(): Unit = closedFlag.set(true)

  /** Whether [[markClosed]] has run — i.e. the flow that created this backend
    * (and every agent handle sharing it) has ended.
    */
  private[orca] final def isClosed: Boolean = closedFlag.get()

object AgentBackend:
  /** The use-after-close guard's user-facing message, thrown by every
    * `isClosed` gate so a leaked-handle failure reads identically no matter
    * which gate caught it.
    */
  private[orca] val ClosedMessage: String =
    "agent used after its flow ended — agents are scoped to the flow(...) that created them"
