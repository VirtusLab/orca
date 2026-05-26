package orca.backend

import orca.events.OrcaListener
import orca.llm.{BackendTag, LlmConfig, SessionId}

/** SPI implemented per backend (Claude, Codex, …). The framework calls these
  * methods from the autonomous-text and structured-output paths
  * ([[AutonomousTextCall]], [[LlmCall]]).
  *
  * Each method takes a `session: SessionId[B]` — the framework hands the same
  * value across calls; the backend decides internally whether this is the
  * first invocation (and the session needs creating) or a continuation. Two
  * methods cover the UX shape: `runAutonomous` runs to completion off-screen
  * and returns the result; `runInteractive` returns a live [[Conversation]]
  * the caller drives through an [[Interaction]].
  *
  * `prompt` on every method is the full wire-level message sent to the agent —
  * with whatever template scaffolding, schema, and rules the caller wrapped
  * around the user's input. `displayPrompt` (interactive only) is what the
  * renderer shows the user; autonomous has no renderer, hence no
  * `displayPrompt`.
  *
  * `workDir` is the working directory the agent subprocess sees.
  */
trait LlmBackend[B <: BackendTag]:
  /** Run one autonomous turn against `session` and return its result. The
    * backend decides whether to create the session (first call with this id)
    * or resume it (subsequent calls).
    *
    * `events` receives per-tool-use and per-message progress as the subprocess
    * runs, so the user has something to watch while the agent works. Defaults
    * to a no-op listener for callers (typically tests) that don't observe
    * progress.
    */
  def runAutonomous(
      prompt: String,
      session: SessionId[B],
      config: LlmConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop
  ): LlmResult[B]

  /** Launch an interactive session against `session` and return a live
    * [[Conversation]] the caller hands to [[Interaction.drive]] for rendering
    * and user steering. The backend owns the subprocess and event parsing;
    * the channel owns UX.
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
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[B]
