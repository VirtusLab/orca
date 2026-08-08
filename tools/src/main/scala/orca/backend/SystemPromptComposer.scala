package orca.backend

import orca.agents.{AgentConfig, ToolSet}
import orca.util.PromptResource

/** Assembles a backend-agnostic system-prompt body from the configured
  * [[AgentConfig.systemPrompt]], an optional `extraHint` (typically the
  * `ask_user` MCP hint on interactive calls), and the standing
  * [[RuntimeOwnsGit]] / [[ReadOnlyTurn]] / [[BackgroundWorkAbandonedAtTurnEnd]]
  * rules, joining non-empty pieces with a blank line.
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
    * into a `NothingToCommit` no-op and leaves `git.uncommittedDiff()` empty,
    * so reviewer selection sees no changed files and runs no reviewers. Omitted
    * on read-only turns and on [[AgentConfig.selfManagedGit]] turns.
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

  /** Standing rule appended to every read-only turn (`ReadOnly` and
    * `NetworkOnly`), which is what makes a `PromptOnly` cell of the
    * [[orca.agents.Enforcement]] matrix true by construction: on a backend that
    * encodes no mechanical gate, this text is the restriction. Appended on the
    * hard-gated backends too — redundant there, but it needs no enforcement
    * plumbing and stays correct if a cell is later reclassified.
    *
    * It forbids state changes without mentioning the network, so a
    * `NetworkOnly` turn keeps the read-only network access it was given.
    */
  val ReadOnlyTurn: String =
    PromptResource.load("/orca/backend/prompts/readonly-turn.md")

  def combine(
      config: AgentConfig,
      extraHint: Option[String] = None
  ): String =
    // The two tool-gated rules are complementary: `Full` gets the git rule
    // (unless `selfManagedGit` says the agent drives git itself — a question
    // about the repo, not about what survives the turn boundary), the read-only
    // tiers get the read-only rule.
    val toolRule = config.tools match
      case ToolSet.Full =>
        Option.when(!config.selfManagedGit)(RuntimeOwnsGit)
      case ToolSet.ReadOnly | ToolSet.NetworkOnly => Some(ReadOnlyTurn)
    List(config.systemPrompt, extraHint, toolRule).flatten
      .appended(BackgroundWorkAbandonedAtTurnEnd)
      .mkString("\n\n")

  /** Fold the composed system prompt into `userPrompt` as a `"System
    * guidance:"` preamble, for backends whose CLI has no
    * `--append-system-prompt` flag (codex, gemini).
    *
    * Called for every turn of a thread, including resumed ones, so a long
    * codex/gemini chat carries one copy per turn. That is deliberate: the
    * guidance is about the turn being executed — [[RuntimeOwnsGit]],
    * [[ReadOnlyTurn]] and [[BackgroundWorkAbandonedAtTurnEnd]] all describe
    * THIS turn — and restating it keeps it recent rather than buried at the top
    * of the thread. It also means a resumed turn carries the restriction text
    * whatever its flags do — the belt to codex's re-applied sandbox braces.
    */
  def foldIntoPrompt(
      config: AgentConfig,
      userPrompt: String,
      extraHint: Option[String] = None
  ): String =
    // No `stripMargin`: `userPrompt` carries the diffs, lint output and review
    // issues that start lines with `|`, which it would eat.
    s"System guidance:\n${combine(config, extraHint)}\n\n" +
      s"User request:\n$userPrompt"
