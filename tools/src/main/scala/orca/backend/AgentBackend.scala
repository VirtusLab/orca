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

/** SPI implemented per backend (Claude, Codex, …). The framework calls these
  * methods from the autonomous-text and structured-output paths
  * ([[AutonomousTextCall]], [[AgentCall]]).
  *
  * Each method takes a `session: SessionId[B]` — the framework hands the same
  * value across calls; the backend decides internally whether this is the first
  * invocation (and the session needs creating) or a continuation. Two methods
  * cover the UX shape: `runAutonomous` runs to completion off-screen and
  * returns the result; `runInteractive` returns a live [[Conversation]] the
  * caller drives through an [[Interaction]].
  *
  * `prompt` on every method is the full wire-level message sent to the agent —
  * with whatever template scaffolding, schema, and rules the caller wrapped
  * around the user's input. `displayPrompt` (interactive only) is what the
  * renderer shows the user; autonomous has no renderer, hence no
  * `displayPrompt`.
  *
  * `workDir` is fixed at construction (see [[workDir]]) rather than threaded
  * per call — the runtime never varies it across a backend's lifetime, so
  * carrying it as a per-call parameter was a phantom degree of freedom.
  */
trait AgentBackend[B <: BackendTag](
    /** Backing store for [[isClosed]]/[[markClosed]]. Defaults to a fresh,
      * unshared flag, correct for every backend whose builders (`withConfig`,
      * `withModel`, …) go through `BaseAgent.copyTool` and so stay on the SAME
      * backend instance. A backend whose builder instead constructs a SIBLING
      * backend instance (today only claude's `withNetworkTools`, see
      * [[orca.tools.claude.ClaudeBackend.withNetworkTools]]) MUST pass the
      * parent's `closedFlag` into the sibling's constructor here, so
      * `markClosed()` on either instance is visible through both — otherwise a
      * handle derived via that builder and leaked past flow-end bypasses the
      * use-after-close guard entirely (it latches a flag nothing else reads).
      */
    private[orca] val closedFlag: AtomicBoolean = new AtomicBoolean(false)
):
  /** Run one autonomous turn against `session` and return its result. The
    * backend decides whether to create the session (first call with this id) or
    * resume it (subsequent calls).
    *
    * `events` receives per-tool-use and per-message progress as the subprocess
    * runs, so the user has something to watch while the agent works. Defaults
    * to a no-op listener for callers (typically tests) that don't observe
    * progress.
    *
    * `outputSchema`, when supplied, is the JSON Schema the final assistant
    * payload must conform to. Backends that enforce schemas natively (claude's
    * `--json-schema`) pass it to the CLI; backends that don't can ignore it.
    * Either way the schema is forwarded to the conversation so the autonomous
    * drain can recognise "the agent's last message IS the structured payload"
    * and suppress the raw JSON from the user log — the caller surfaces it via
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
    * [[Conversation]] the caller hands to [[Interaction.drive]] for rendering
    * and user steering. The backend owns the subprocess and event parsing; the
    * channel owns UX.
    *
    * `outputSchema` is the JSON Schema the agent's final reply must conform to,
    * or `None` for free-form text. Backends that support structured-output
    * validation (claude's `--json-schema`) enforce it; those that don't can
    * ignore the parameter and let the caller validate post-hoc.
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
    * against this SAME path, by construction (no separate per-call value can
    * drift from it).
    */
  def workDir: os.Path

  /** This backend's whole session capability as one structural value: the id
    * scheme plus, for durable backends, the existence probe (see
    * [[SessionSupport.durable]] / [[SessionSupport.ephemeral]]). The framework
    * reaches sessions exclusively through this — a backend cannot half-wire
    * resume by providing one of persist/probe/register and forgetting the
    * others.
    */
  def sessions: SessionSupport[B]

  /** Runtime value of the compile-time tag `B`; lets the runtime record which
    * backend a session belongs to.
    */
  def tag: B

  /** How strongly THIS backend enforces the restriction that a `(tools,
    * autoApprove)` combination requests — a pure classification of the flags
    * this backend's `*Args` would build, with no side effects. The mapping
    * differs materially across backends (a `Full` + `AutoApprove.Only` is a
    * mechanical allowlist on claude but a whole-sandbox approximation on codex
    * and unencoded on the rest), so it is surfaced as data rather than left in
    * scattered scaladoc.
    *
    * The complete matrix is machine-checked in
    * `runner/src/test/scala/orca/runner/EnforcementTableTest.scala` — that test
    * is the human-readable source of truth; each backend implements this by
    * delegating to its `*Args.enforcement`, where the per-cell rationale lives.
    *
    * Abstract, not defaulted to `Enforcement.Ignored`: a silent fallback would
    * let a new backend ship without ever answering this and without anyone
    * noticing (the matrix test only helps if its author remembers to add a
    * row). REAL backends must implement this AND add their rows to
    * `EnforcementTableTest` (which pins the five shipped ones); test doubles
    * that never call `enforcement` add a one-line override — conservatively
    * `Enforcement.Ignored` — same as they'd have gotten from the old default.
    */
  def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement

  /** How THIS backend's wire delivers a structured (`resultAs[O]`) payload — as
    * a CLI-injected StructuredOutput tool call, or as the reply text
    * ([[orca.agents.StructuredOutputMode]]). Prompt assembly
    * ([[orca.agents.Prompts.autonomous]]) branches on this declaration, so a
    * backend that misdeclares gets an instruction that contradicts its wire and
    * steers weak models into malformed replies.
    *
    * Abstract, not defaulted, for the same reason as [[enforcement]]: a silent
    * fallback would let a new backend ship without ever answering this. REAL
    * backends declare what their CLI actually does (see each override's
    * evidence comment); test doubles that never assemble prompts add a one-line
    * `RawText` override.
    */
  def structuredOutputMode: StructuredOutputMode

  /** Release background resources this backend owns (processes, servers, drain
    * forks). Called by the runtime in the flow body's `finally`, BEFORE the
    * flow scope joins its forks — a resource whose teardown unblocks a
    * non-interruptible read must happen here, not in a `releaseAfterScope`
    * finalizer (Ox runs those after the join). Idempotent; default no-op.
    */
  def close(): Unit = ()

  // The use-after-close latch lives HERE, on the backend, not on the Agent
  // instance: every builder (`withConfig`, `withModel`, `opus`, `withName`, …)
  // goes through `BaseAgent.copyTool`, which constructs a NEW agent instance
  // sharing this same backend — a per-agent flag would silently reset to
  // "open" on every derived handle, letting `leaked.opus.autonomous.run(...)`
  // bypass the guard. `closedFlag` itself is a constructor parameter (see
  // above) rather than a fresh field here, so a backend-swapping builder can
  // thread the SAME flag into a sibling instance instead of resetting it.

  /** Latch this backend as closed — its owning flow has ended, and every run
    * entry point gated on [[isClosed]] must refuse from now on. Called by
    * `BaseAgent.close()` alongside (before) [[close]]; separate from [[close]]
    * so a subclass overriding `close()` for resource teardown cannot forget the
    * latch.
    */
  private[orca] final def markClosed(): Unit = closedFlag.set(true)

  /** Whether [[markClosed]] has run — i.e. the flow that created this backend
    * (and every agent handle sharing it) has ended.
    */
  private[orca] final def isClosed: Boolean = closedFlag.get()

object AgentBackend:
  /** The use-after-close guard's user-facing message, thrown by every
    * `isClosed` gate (`BaseAgent.checkNotClosed`,
    * `DefaultAgentCall.checkNotClosed`) so a leaked-handle failure reads
    * identically no matter which gate caught it. Single home for the string
    * rather than one literal copy per gate.
    */
  private[orca] val ClosedMessage: String =
    "agent used after its flow ended — agents are scoped to the flow(...) that created them"
