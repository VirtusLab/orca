package orca.backend

import orca.agents.{AgentConfig, ToolSet}

/** Assembles a backend-agnostic system-prompt body from the configured
  * [[AgentConfig.systemPrompt]], an optional `extraHint` (typically the
  * `ask_user` MCP hint on interactive calls), and the standing
  * [[RuntimeOwnsGit]] and [[NoBackgroundWork]] rules, joining non-empty pieces
  * with a blank line.
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

  /** Standing rule appended to every write-capable agent turn: the backend CLI
    * is a fresh process per turn and is torn down when the turn ends, so
    * anything the agent backgrounded dies with it and its output is never read
    * back. Agents background long builds defensively — correct in an
    * interactive harness, harmful here: the command is killed, the agent
    * reports the work as done without ever seeing a result, and the review loop
    * then spends rounds on unverified fixes. Omitted on non-[[ToolSet.Full]]
    * turns, which read and report rather than run builds — orca itself executes
    * the lint commands and hands a read-only agent only their captured output.
    */
  val NoBackgroundWork: String =
    "Your process is torn down at the end of this turn. Any command or monitor " +
      "you leave running in the background is killed and you will never see " +
      "its output. Run long commands (builds, test suites) in the FOREGROUND " +
      "and wait for them to finish — there is no per-turn timeout, so a " +
      "15-minute build is fine. Never report a result you have not actually " +
      "observed."

  def combine(
      config: AgentConfig,
      extraHint: Option[String] = None
  ): Option[String] =
    val writeCapable = config.tools == ToolSet.Full
    val gitRule =
      if !writeCapable || config.selfManagedGit then None
      else Some(RuntimeOwnsGit)
    // Deliberately not gated on `selfManagedGit`: that flag says who drives
    // git, which has no bearing on process lifetime.
    val backgroundRule = if writeCapable then Some(NoBackgroundWork) else None
    List(config.systemPrompt, extraHint, gitRule, backgroundRule).flatten match
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
