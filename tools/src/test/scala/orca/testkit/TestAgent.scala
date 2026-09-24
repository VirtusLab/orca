package orca.testkit

import orca.agents.{Agent, AgentConfig, BackendTag, DefaultPrompts, Prompts}
import orca.backend.{AgentBackend, AgentResult, Interaction, ObservedTurn}
import orca.events.OrcaListener
import ox.scheduling.Schedule

import scala.concurrent.duration.DurationInt

/** A real [[Agent]] over a test backend — the way tests stand in for an LLM:
  * the backend double scripts the replies, the agent is the production one.
  */
object TestAgent:
  def apply[B <: BackendTag](
      backend: AgentBackend[B],
      name: String = "stub",
      config: AgentConfig = FastRetry,
      events: OrcaListener = OrcaListener.noop,
      prompts: Prompts = DefaultPrompts,
      interaction: Interaction = UnusedInteraction
  ): Agent[B] =
    Agent(backend, config, prompts, events, interaction, defaultName = name)

  /** The production retry count without its backoff delay. */
  val FastRetry: AgentConfig =
    AgentConfig(retrySchedule = Schedule.fixedInterval(1.milli).maxRetries(3))

  /** For agents whose tests never drive an interactive turn. */
  object UnusedInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        turn: ObservedTurn[B]
    ): AgentResult[B] =
      throw new UnsupportedOperationException("no interactive turn expected")
