package orca.backend

import orca.events.OrcaListener
import orca.llm.{BackendTag, LlmConfig, SessionId}

/** SPI implemented per backend (Claude, Codex, …). The framework calls these
  * methods from the autonomous-text and structured-output paths
  * ([[AutonomousTextCall]], [[LlmCall]]).
  *
  * Two pairs of methods cover the cartesian product of session-retention
  * (`run*` starts a new session, `continue*` resumes one via `sessionId`) and
  * UX shape (`*Headless` runs to completion off-screen and returns the result;
  * `*Interactive` returns a live [[Conversation]] the caller drives through an
  * [[Interaction]]).
  *
  * `prompt` on every method is the full wire-level message sent to the agent —
  * with whatever template scaffolding, schema, and rules the caller wrapped
  * around the user's input. `displayPrompt` (interactive only) is what the
  * renderer shows the user; headless has no renderer, hence no `displayPrompt`.
  *
  * `workDir` is the working directory the agent subprocess sees.
  */
trait LlmBackend[B <: BackendTag]:
  /** Run one autonomous turn to completion and return its result. No user
    * interaction; suitable for the `autonomous.run` / `resultAs[O].autonomous`
    * paths.
    *
    * `events` receives per-tool-use and per-message progress as the subprocess
    * runs, so the user has something to watch while the agent works. Defaults
    * to a no-op listener for callers (typically tests) that don't observe
    * progress.
    */
  def runHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop
  ): LlmResult[B]

  /** Resume an existing session for one more autonomous turn. `sessionId` is a
    * value previously returned by [[runHeadless]] or by
    * [[AutonomousTextCall.startSession]]. Same UX guarantees as
    * [[runHeadless]].
    */
  def continueHeadless(
      sessionId: SessionId[B],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop
  ): LlmResult[B]

  /** Launch an interactive session and return a live [[Conversation]] the
    * caller hands to an [[Interaction.drive]] for rendering and user steering.
    * The backend owns the subprocess and event parsing; the channel owns UX.
    *
    * `outputSchema` is the JSON Schema the agent's final reply must conform to,
    * or `None` for free-form text. Backends that support structured-output
    * validation (claude's `--json-schema`) enforce it; those that don't can
    * ignore the parameter and let the caller validate post-hoc.
    */
  def runInteractive(
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[B]

  /** Resume an existing session as a live [[Conversation]]. Same contract as
    * [[runInteractive]] otherwise.
    */
  def continueInteractive(
      sessionId: SessionId[B],
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[B]
