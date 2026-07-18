package orca.backend

import orca.agents.{AgentConfig, ToolSet}

/** Assembles a backend-agnostic system-prompt body from the configured
  * [[AgentConfig.systemPrompt]], an optional `extraHint` (typically the
  * `ask_user` MCP hint on interactive calls), and the standing
  * [[RuntimeOwnsGit]] rule, joining non-empty pieces with a blank line.
  *
  * Returns `None` when nothing applies. Each backend delivers the result its
  * own way — claude writes it to a temp file for `--append-system-prompt-file`;
  * codex and gemini have no such flag and use [[foldIntoPrompt]].
  */
private[orca] object SystemPromptComposer:

  /** Standing rule appended to every write-capable agent turn: orca's runtime
    * owns git, so the agent must never commit, push, or switch branches itself.
    * Without it, coding agents routinely `git commit` their own work, which
    * empties the working tree and then turns the flow's own `git.commit(...)`
    * into a `NothingToCommit` no-op and leaves `git.diff()` empty, so reviewer
    * selection sees no changed files and runs no reviewers. Omitted on
    * read-only turns and on [[AgentConfig.selfManagedGit]] turns.
    */
  val RuntimeOwnsGit: String =
    "Git is managed by the runtime. Do NOT run `git commit`, `git push`, or " +
      "create/switch branches — make your edits and leave them uncommitted in " +
      "the working tree; the surrounding flow commits, branches, and pushes at " +
      "the right points."

  def combine(
      config: AgentConfig,
      extraHint: Option[String] = None
  ): Option[String] =
    val gitRule =
      if config.tools != ToolSet.Full || config.selfManagedGit then None
      else Some(RuntimeOwnsGit)
    List(config.systemPrompt, extraHint, gitRule).flatten match
      case Nil    => None
      case pieces => Some(pieces.mkString("\n\n"))

  /** Fold the composed system prompt into `userPrompt` as a `"System
    * guidance:"` preamble, for backends whose CLI has no
    * `--append-system-prompt` flag (codex, gemini). Returns `userPrompt`
    * unchanged when nothing applies.
    */
  def foldIntoPrompt(
      config: AgentConfig,
      userPrompt: String,
      extraHint: Option[String] = None
  ): String =
    combine(config, extraHint) match
      case None => userPrompt
      case Some(text) =>
        s"""System guidance:
           |$text
           |
           |User request:
           |$userPrompt""".stripMargin
