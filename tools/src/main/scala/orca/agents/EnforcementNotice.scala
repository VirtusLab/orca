package orca.agents

import orca.backend.AgentBackend
import orca.events.{OrcaEvent, OrcaListener}

import org.slf4j.LoggerFactory

/** Reports a turn whose requested no-edit tier the backend does not
  * mechanically enforce — the one runtime consumer of [[Enforcement]].
  *
  * Without it, a caller that asked for `withReadOnly` and got prose has no
  * signal anywhere: a run in which a reviewer drifted from reporting into
  * editing looks exactly like one in which editing was impossible, and telling
  * them apart means already knowing the matrix.
  *
  * Two channels, because they have different readers: a short `Step` in the
  * run's own stream, and a WARN carrying the cell's rationale in the log.
  * [[AgentBackend.enforcementShortfall]] does the deduplication, so a
  * twenty-reviewer fan-out says this once.
  */
private[orca] object EnforcementNotice:

  private val log = LoggerFactory.getLogger(getClass)

  def announceShortfall[B <: BackendTag](
      backend: AgentBackend[B],
      config: AgentConfig,
      dispatch: TurnDispatch,
      events: OrcaListener
  ): Unit =
    backend
      .enforcementShortfall(config.tools, config.autoApprove, dispatch)
      .foreach: cell =>
        val summary =
          s"${backend.tag.wireName} does not mechanically enforce ${config.tools} " +
            s"on a ${dispatch.toString.toLowerCase} turn: ${cell.level}"
        events.onEvent(OrcaEvent.Step(summary))
        log.warn("{} — {}", summary, cell.rationale)
