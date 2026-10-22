package orca.agents

import orca.events.{OrcaEvent, OrcaListener}
import orca.safe.Logger
import orca.concurrency.Ref

/** Says, when a turn asks a backend for a restriction it cannot mechanically
  * apply, that the restriction is not mechanical — the runtime's only consumer
  * of [[Enforcement]]. Without it, a run in which a reviewer drifted from
  * reporting into editing looks exactly like one in which editing was
  * impossible.
  *
  * Two channels, because they have different readers: the `Caveat` a run shows
  * says in plain words what the agent may still do, and the WARN behind it
  * carries the cell's rationale and level names for whoever is diagnosing.
  *
  * One instance per backend, held by [[orca.backend.AgentBackend]] and shared
  * with any sibling backend derived from it, so a twenty-reviewer fan-out says
  * each thing once.
  *
  * Said at the first turn that asks, not at run start: a flow picks a turn's
  * `ToolSet` when it makes the call, so the run header cannot know the tiers.
  */
private[orca] final class EnforcementNotice:

  // Shared across a fan-out's reviewer forks, so neither method-local nor
  // `caps.Mutable` (fails separation checking there); no I/O in the update, so
  // no actor.
  /** Sentences already said. Keyed on the sentence rather than the cell, so a
    * different tier, dispatch or approval list repeats the notice exactly when
    * it changes the wording.
    */
  private val said: Ref[Set[String]] = Ref(Set.empty)

  /** Report `config`'s unmet restriction for the turn about to run as
    * `dispatch` on backend `tag`, unless the same sentence has already been
    * said. `cell` is backend `tag`'s cell for `config` and `dispatch`;
    * `freshCell` the one for `config` on a fresh dispatch.
    *
    * Reporting, not refusing: a weaker gate is a documented property of the
    * backend, and a restricted turn's prompt still carries
    * [[orca.backend.SystemPromptComposer.ReadOnlyTurn]].
    */
  def announceShortfall(
      tag: BackendTag,
      config: AgentConfig,
      dispatch: TurnDispatch,
      cell: EnforcementCell,
      freshCell: EnforcementCell,
      events: OrcaListener
  ): Unit =
    EnforcementNotice
      .summary(tag, config, dispatch, cell, freshCell)
      .foreach: summary =>
        if said.modify(s => (s + summary, !s.contains(summary))) then
          events.onEvent(OrcaEvent.Caveat(summary))
          EnforcementNotice.log.warn(s"$summary — ${cell.rationale}")

private[orca] object EnforcementNotice:

  private val log = Logger(classOf[EnforcementNotice].getName)

  /** The sentence to say, or `None` when the turn asked for nothing this
    * backend had to encode, or got what it asked for.
    */
  private def summary(
      tag: BackendTag,
      config: AgentConfig,
      dispatch: TurnDispatch,
      cell: EnforcementCell,
      freshCell: EnforcementCell
  ): Option[String] =
    for
      request <- unmetRequest(
        config,
        turnWording(config, dispatch, cell, freshCell)
      )
      consequence <- consequenceOf(cell.level)
    yield s"$tag cannot $request — $consequence"

  /** What the caller asked this backend to withhold. The two axes a caller can
    * restrict on: the tier withholds the write tools (or, on `NoTools`, every
    * tool), [[AutoApprove.Only]] withholds unprompted use of everything else.
    * `Full` + `All` asked for neither, so there is nothing it can fail to get.
    */
  private def unmetRequest(config: AgentConfig, turn: String): Option[String] =
    config.tools match
      case ToolSet.ReadOnly | ToolSet.NetworkOnly =>
        Some(
          s"stop $turn from editing files or running commands that change state"
        )
      case ToolSet.NoTools =>
        Some(s"stop $turn from calling tools")
      case ToolSet.Full =>
        config.autoApprove match
          case AutoApprove.All     => None
          case AutoApprove.Only(_) =>
            Some(s"hold $turn to the tools it was asked to auto-approve")

  /** What the reader can act on, in place of the level's name — which stays in
    * the WARN, where the vocabulary is already in play.
    */
  private def consequenceOf(level: Enforcement): Option[String] =
    level match
      case Enforcement.Hard       => None
      case Enforcement.PromptOnly =>
        Some("only the turn's own prompt asks it not to")
      case Enforcement.SandboxApprox =>
        Some("the sandbox it runs in is wider than that")
      case Enforcement.Ignored =>
        Some("nothing orca puts on the wire says so")

  /** Names the resumed turn ONLY where resuming is what weakened the answer —
    * without that, a notice that fires at resume time alone reads as if the
    * restriction had never held (codex's `Only` approximation, which has no
    * sandbox of its own to re-apply). Naming it unconditionally would instead
    * split one fact into two sentences on every backend the dispatch doesn't
    * change.
    */
  private def turnWording(
      config: AgentConfig,
      dispatch: TurnDispatch,
      cell: EnforcementCell,
      freshCell: EnforcementCell
  ): String =
    val weakenedByResuming = cell.level.weakerThan(freshCell.level)
    dispatch match
      case TurnDispatch.Resumed if weakenedByResuming =>
        s"a resumed ${config.tools} turn"
      case TurnDispatch.Fresh | TurnDispatch.Resumed =>
        s"a ${config.tools} turn"
