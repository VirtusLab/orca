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
    * run, where a background command genuinely does survive.
    *
    * It says the abandoned command may be killed OR keep running, which holds
    * either side of the teardown change in flight: today
    * `ForkedConversation.cancel` destroys the CLI's own PID and not its process
    * tree, so a backgrounded build is orphaned and can still write into the
    * work dir while the flow commits; routing teardown through
    * `destroyForciblyTree` would make the kill real. Both outcomes lose the
    * result, which is what the agent needs to know.
    */
  val BackgroundWorkAbandonedAtTurnEnd: String =
    "When this turn ends, orca stops reading your output. Anything left " +
      "running in the background is abandoned: you will never see its result, " +
      "and it will either be killed or keep running unsupervised where it can " +
      "corrupt the commit the flow is about to make. Never report a result you " +
      "have not observed. Run any command whose result you need — a build, a " +
      "test suite — in the foreground and wait for it to finish within this " +
      "turn; orca itself does not time out a turn. Backgrounding is fine only " +
      "for something you start, use and stop inside this same turn, such as a " +
      "server you then test against. If a command you started produces no " +
      "output for several minutes, or your tooling cuts it short, stop it and " +
      "report that result as unverified — an escape valve for a command you " +
      "ran and waited on, never a substitute for running it."

  /** Always `Some`: a standing rule applies to every turn, so there is nothing
    * to compose that could come out empty. The `Option` is kept because every
    * backend's delivery path is written around it — narrowing it would flip
    * each of their system-prompt flags to unconditional.
    */
  def combine(
      config: AgentConfig,
      extraHint: Option[String] = None
  ): Option[String] = Some(composeAll(config, extraHint))

  private def composeAll(
      config: AgentConfig,
      extraHint: Option[String]
  ): String =
    // Only the git rule is tool-gated. It is additionally suppressed by
    // `selfManagedGit`, which says who drives git — a question about the repo,
    // not about what survives the turn boundary.
    val gitRule = Option.when(
      config.tools == ToolSet.Full && !config.selfManagedGit
    )(RuntimeOwnsGit)
    List(config.systemPrompt, extraHint, gitRule).flatten
      .appended(BackgroundWorkAbandonedAtTurnEnd)
      .mkString("\n\n")

  /** Fold the composed system prompt into `userPrompt` as a `"System
    * guidance:"` preamble, for backends whose CLI has no
    * `--append-system-prompt` flag (codex, gemini).
    *
    * Called for every turn of a thread, including resumed ones, so a long
    * codex/gemini chat carries one copy per turn. That is deliberate: the
    * guidance is about the turn being executed — [[RuntimeOwnsGit]] and
    * [[BackgroundWorkAbandonedAtTurnEnd]] both describe what happens at THIS
    * turn's end — and restating it keeps it recent rather than buried at the
    * top of the thread.
    */
  def foldIntoPrompt(
      config: AgentConfig,
      userPrompt: String,
      extraHint: Option[String] = None
  ): String =
    s"""System guidance:
       |${composeAll(config, extraHint)}
       |
       |User request:
       |$userPrompt""".stripMargin
