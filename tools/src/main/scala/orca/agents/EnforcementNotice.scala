package orca.agents

import orca.backend.AgentBackend
import orca.events.{OrcaEvent, OrcaListener}

import org.slf4j.LoggerFactory

/** Says, when a turn asks a backend for a restriction it cannot mechanically
  * apply, that the restriction is not mechanical — the runtime's only consumer
  * of [[Enforcement]].
  *
  * Without it, a caller that asked for `withReadOnly` and got prose has no
  * signal anywhere: a run in which a reviewer drifted from reporting into
  * editing looks exactly like one in which editing was impossible, and telling
  * them apart means already knowing the matrix.
  *
  * Two channels, because they have different readers: the `Step` a run shows
  * says in plain words what the agent may still do, and the WARN behind it
  * carries the cell's rationale and level names for whoever is diagnosing.
  *
  * One instance per backend, held by [[AgentBackend]] and shared with any
  * sibling backend derived from it, so a twenty-reviewer fan-out says each
  * thing once.
  */
private[orca] final class EnforcementNotice:

  /** Rendered `Step` lines already said. Keying on the sentence itself is what
    * makes "already said" mean what a reader would mean by it: a different
    * tier, dispatch or approval list repeats the notice exactly when it changes
    * the sentence, and stays silent when it only changes the reasoning behind
    * an identical one.
    */
  private val said =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** Report `config`'s unmet restriction for the turn about to run against
    * `session`, unless the same sentence has already been said.
    *
    * Resolves the dispatch itself, from the same `dispatchFor` the backend will
    * consult moments later — the one derivation, so a caller cannot pair a
    * fresh-turn classification with a resumed turn.
    *
    * Reporting, not refusing: a weaker gate is a documented property of the
    * backend, and a restricted turn's prompt still carries
    * [[orca.backend.SystemPromptComposer.ReadOnlyTurn]].
    */
  def announceShortfall[B <: BackendTag](
      backend: AgentBackend[B],
      config: AgentConfig,
      session: SessionId[B],
      events: OrcaListener
  ): Unit =
    val dispatch = backend.sessions.dispatchFor(session).asTurnDispatch
    val cell =
      backend.enforcementCell(config.tools, config.autoApprove, dispatch)
    EnforcementNotice
      .summary(backend, config, cell, dispatch)
      .foreach: summary =>
        if said.add(summary) then
          events.onEvent(OrcaEvent.Step(summary))
          EnforcementNotice.log.warn("{} — {}", summary, cell.rationale)

private[orca] object EnforcementNotice:

  private val log = LoggerFactory.getLogger(classOf[EnforcementNotice])

  /** The sentence to say, or `None` when the turn asked for nothing this
    * backend had to encode, or got what it asked for.
    */
  private def summary[B <: BackendTag](
      backend: AgentBackend[B],
      config: AgentConfig,
      cell: EnforcementCell,
      dispatch: TurnDispatch
  ): Option[String] =
    for
      request <- unmetRequest(
        config,
        turnWording(backend, config, cell, dispatch)
      )
      consequence <- consequenceOf(cell.level)
    yield s"${backend.tag.wireName} cannot $request — $consequence"

  /** What the caller asked this backend to withhold. The two arms are the two
    * axes a caller can restrict on: the tier withholds the write tools,
    * [[AutoApprove.Only]] withholds unprompted use of everything else. `Full` +
    * `All` asked for neither, so there is nothing it can fail to get.
    */
  private def unmetRequest(config: AgentConfig, turn: String): Option[String] =
    config.tools match
      case ToolSet.ReadOnly | ToolSet.NetworkOnly =>
        Some(
          s"stop $turn from editing files or running commands that change state"
        )
      case ToolSet.Full =>
        config.autoApprove match
          case AutoApprove.All => None
          case AutoApprove.Only(_) =>
            Some(s"hold $turn to the tools it was asked to auto-approve")

  /** What the reader can act on, in place of the level's name — which stays in
    * the WARN, where the vocabulary is already in play.
    */
  private def consequenceOf(level: Enforcement): Option[String] =
    level match
      case Enforcement.Hard => None
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
  private def turnWording[B <: BackendTag](
      backend: AgentBackend[B],
      config: AgentConfig,
      cell: EnforcementCell,
      dispatch: TurnDispatch
  ): String =
    val weakenedByResuming = cell.level.weakerThan(
      backend
        .enforcementCell(config.tools, config.autoApprove, TurnDispatch.Fresh)
        .level
    )
    dispatch match
      case TurnDispatch.Resumed if weakenedByResuming =>
        s"a resumed ${config.tools} turn"
      case TurnDispatch.Fresh | TurnDispatch.Resumed =>
        s"a ${config.tools} turn"
