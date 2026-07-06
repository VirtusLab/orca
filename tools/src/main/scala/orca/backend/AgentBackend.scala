package orca.backend

import orca.events.OrcaListener
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  SessionId,
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
  * `workDir` is the working directory the agent subprocess sees.
  */
trait AgentBackend[B <: BackendTag]:
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
      workDir: os.Path,
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
      workDir: os.Path,
      outputSchema: Option[String]
  )(using Ox): Conversation[B]

  /** This backend's session-durability capability as one structural value: a
    * [[SessionSupport.Durable]] (registry + existence probe) or a
    * [[SessionSupport.Ephemeral]] (registry only). The framework reaches
    * durability exclusively through this — a backend cannot half-wire resume by
    * providing one of persist/probe/register and forgetting the others.
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
    * The default is the conservative "not encoded" answer, so an audit surfaces
    * an under-promise rather than a false guarantee. REAL backends MUST
    * override this AND add their rows to `EnforcementTableTest` (which pins the
    * five shipped ones); the default exists only to spare test doubles a
    * meaningless override.
    */
  def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
    Enforcement.Ignored
