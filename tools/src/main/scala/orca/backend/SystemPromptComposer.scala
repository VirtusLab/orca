package orca.backend

import orca.llm.LlmConfig

/** Shared helper for assembling a backend-agnostic "system prompt body" from
  * the configured [[LlmConfig.systemPrompt]] plus an optional caller-supplied
  * `extraHint` (typically the shared `ask_user` MCP hint on interactive calls).
  * Concatenates non-empty pieces with a blank line between them.
  *
  * Returns `None` when neither piece is present. Each backend decides how to
  * deliver the resulting string — claude writes it to a temp file for
  * `--append-system-prompt-file`; codex folds it into the user prompt as a
  * `"System guidance:"` preamble (codex has no `--append-system-prompt`).
  */
private[orca] object SystemPromptComposer:
  def combine(
      config: LlmConfig,
      extraHint: Option[String] = None
  ): Option[String] =
    List(config.systemPrompt, extraHint).flatten match
      case Nil    => None
      case pieces => Some(pieces.mkString("\n\n"))
