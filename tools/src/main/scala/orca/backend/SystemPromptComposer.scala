package orca.backend

import orca.agents.{AgentConfig, ToolSet}
import orca.util.PromptResource

/** Assembles a backend-agnostic system-prompt body from the configured
  * [[AgentConfig.systemPrompt]], an optional `extraHint` (typically the
  * `ask_user` MCP hint on interactive calls), and the standing
  * [[RuntimeOwnsGit]] and [[BackgroundWorkAbandonedAtTurnEnd]] rules, joining
  * non-empty pieces with a blank line.
  *
  * Each backend delivers the result its own way — claude writes it to a temp
  * file for `--append-system-prompt-file`; codex and gemini have no such flag
  * and use [[foldIntoPrompt]].
  *
  * The rule texts live as `.md` resources under
  * `src/main/resources/orca/backend/prompts/`. Each is a single unwrapped
  * paragraph: the composed prompt joins pieces with blank lines, so a hard wrap
  * in the source file would put line breaks inside a rule.
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
    PromptResource.load("/orca/backend/prompts/runtime-owns-git.md")

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
    PromptResource.load(
      "/orca/backend/prompts/background-work-abandoned-at-turn-end.md"
    )

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
    // No `stripMargin`: `userPrompt` carries the diffs, lint output and review
    // issues that start lines with `|`, which it would eat.
    s"System guidance:\n${composeAll(config, extraHint)}\n\n" +
      s"User request:\n$userPrompt"
