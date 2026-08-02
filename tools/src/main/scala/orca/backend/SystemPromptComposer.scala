package orca.backend

import orca.agents.{AgentConfig, ToolSet}

/** Assembles a backend-agnostic system-prompt body from the configured
  * [[AgentConfig.systemPrompt]], an optional `extraHint` (typically the
  * `ask_user` MCP hint on interactive calls), and the standing
  * [[RuntimeOwnsGit]] and [[BackgroundWorkAbandonedAtTurnEnd]] rules, joining
  * non-empty pieces with a blank line.
  *
  * Each backend delivers the result its own way — claude writes it to a temp
  * file for `--append-system-prompt-file`; codex and gemini have no such flag
  * and use [[foldIntoPrompt]].
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

  /** Standing rule appended to EVERY agent turn, unlike [[RuntimeOwnsGit]]:
    * orca stops reading a turn's output once the turn ends, so anything the
    * agent left running in the background is abandoned. That is a property of
    * the turn boundary, not of the agent's tools — a read-only agent can still
    * spawn a sub-agent or schedule a wakeup and then wait for a result that can
    * never arrive, which is what a live run cost $1.44 across two reviewer
    * turns. Gating it on [[ToolSet.Full]] would have withheld it from exactly
    * those turns.
    *
    * The rule states the turn boundary rather than a process model, because the
    * two differ per backend: claude, codex, gemini and pi are spawned per turn,
    * while opencode's agent runs inside a `serve` process shared by the whole
    * run, where a background command genuinely does survive. It also says
    * "abandoned", not "killed" — teardown destroys the CLI's own PID, not its
    * process tree (`ForkedConversation.cancel` →
    * `OsProcCliRunner.destroyForcibly`), so an orphan may outlive the turn.
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

  /** Since [[BackgroundWorkAbandonedAtTurnEnd]] applies to every turn the
    * result is currently always `Some`, so each backend's "nothing to send"
    * path is no longer exercised; the `Option` is kept rather than flipping
    * every backend's system-prompt flag to unconditional.
    */
  def combine(
      config: AgentConfig,
      extraHint: Option[String] = None
  ): Option[String] =
    // Only the git rule is tool-gated. It is additionally suppressed by
    // `selfManagedGit`, which says who drives git — a question about the repo,
    // not about what survives the turn boundary.
    val gitRule = Option.when(
      config.tools == ToolSet.Full && !config.selfManagedGit
    )(RuntimeOwnsGit)
    List(
      config.systemPrompt,
      extraHint,
      gitRule,
      Some(BackgroundWorkAbandonedAtTurnEnd)
    ).flatten match
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
