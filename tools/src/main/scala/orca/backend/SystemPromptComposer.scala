package orca.backend

import orca.agents.{AgentConfig, ToolSet}

/** Assembles a backend-agnostic system-prompt body from the configured
  * [[AgentConfig.systemPrompt]], an optional `extraHint` (typically the
  * `ask_user` MCP hint on interactive calls), and the standing
  * [[RuntimeOwnsGit]] and [[BackgroundWorkAbandonedAtTurnEnd]] rules, joining
  * non-empty pieces with a blank line.
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

  /** Standing rule appended to every write-capable agent turn: orca stops
    * reading a turn's output once the turn ends, so anything the agent left
    * running in the background is abandoned. The rule states the turn boundary
    * rather than a process model, because the two differ per backend: claude,
    * codex, gemini and pi are spawned per turn, while opencode's agent runs
    * inside a `serve` process shared by the whole run, where a background
    * command genuinely does survive. It also says "abandoned", not "killed" —
    * teardown destroys the CLI's own PID, not its process tree
    * (`ForkedConversation.cancel` → `OsProcCliRunner.destroyForcibly`), so an
    * orphan may outlive the turn.
    *
    * Gated on [[ToolSet.Full]] alone, unlike [[RuntimeOwnsGit]]: the read-only
    * tiers are never asked to run a build (`lint`'s commands are executed by
    * orca, which hands the agent only their captured output), even though some
    * backends do leave a shell open there.
    */
  val BackgroundWorkAbandonedAtTurnEnd: String =
    "When this turn ends, orca stops reading your output. Anything left " +
      "running in the background is abandoned: you will never see its result, " +
      "and it may be killed at any moment. Run any command whose result you " +
      "need — a build, a test suite — in the foreground and wait for it to " +
      "finish within this turn; orca itself does not time out a turn. " +
      "Backgrounding is fine only for something you start, use and stop inside " +
      "this same turn, such as a server you then test against. If a command " +
      "hangs, or your tooling cuts it short, stop it and report the result as " +
      "unverified — never report a result you have not observed."

  def combine(
      config: AgentConfig,
      extraHint: Option[String] = None
  ): Option[String] =
    val writeCapable = config.tools == ToolSet.Full
    val gitRule =
      Option.when(writeCapable && !config.selfManagedGit)(RuntimeOwnsGit)
    // Deliberately not gated on `selfManagedGit`: that flag says who drives
    // git, which has no bearing on what happens at the turn boundary.
    val backgroundRule =
      Option.when(writeCapable)(BackgroundWorkAbandonedAtTurnEnd)
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
