package orca.backend

import orca.llm.LlmConfig

/** Shared helper for assembling a backend-agnostic "system prompt body" from
  * the configured [[LlmConfig.systemPrompt]], an optional caller-supplied
  * `extraHint` (typically the shared `ask_user` MCP hint on interactive calls),
  * and the standing [[RuntimeOwnsGit]] rule. Concatenates non-empty pieces with
  * a blank line between them.
  *
  * Returns `None` only when nothing applies (a read-only turn with no
  * systemPrompt and no hint). Each backend decides how to deliver the resulting
  * string — claude writes it to a temp file for `--append-system-prompt-file`;
  * codex folds it into the user prompt as a `"System guidance:"` preamble
  * (codex has no `--append-system-prompt`).
  */
private[orca] object SystemPromptComposer:

  /** Standing rule appended to every write-capable agent turn: orca's runtime
    * owns git, so the agent must never commit, push, or switch branches itself.
    * Without it, coding agents routinely `git commit` their own work, which
    * empties the working tree and then (a) turns the flow's own
    * `git.commit(...)` into a `NothingToCommit` no-op, and (b) leaves
    * `git.diff()` empty so `reviewAndFixLoop`'s reviewer selection sees no
    * changed files and runs no reviewers. Omitted on read-only turns (planning,
    * triage, reviewer selection — they can't write anyway). An invariant of
    * orca's runtime-owns-git model: applied on top of any `withSystemPrompt`,
    * not user-overridable.
    */
  val RuntimeOwnsGit: String =
    "Git is managed by the runtime. Do NOT run `git commit`, `git push`, or " +
      "create/switch branches — make your edits and leave them uncommitted in " +
      "the working tree; the surrounding flow commits, branches, and pushes at " +
      "the right points."

  def combine(
      config: LlmConfig,
      extraHint: Option[String] = None
  ): Option[String] =
    val gitRule = if config.readOnly then None else Some(RuntimeOwnsGit)
    List(config.systemPrompt, extraHint, gitRule).flatten match
      case Nil    => None
      case pieces => Some(pieces.mkString("\n\n"))
