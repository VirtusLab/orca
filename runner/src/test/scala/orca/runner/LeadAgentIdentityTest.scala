package orca.runner

import orca.{FlowContext, OrcaArgs, flow}
import orca.agents.{
  AgentConfig,
  AutoApprove,
  BackendTag,
  Enforcement,
  PiAgent,
  ToolSet
}
import orca.backend.{
  AgentBackend,
  AgentResult,
  Conversation,
  Interaction,
  SessionRegistry,
  SessionSupport
}
import orca.events.{OrcaEvent, OrcaListener}
import orca.tools.pi.DefaultPiAgent
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

/** Pins complexity-review-2 10.1: the `agent` selector can return an agent
  * built from a backend that isn't wired into this run's context — event-blind
  * (built against its own `AgentWiring`, not this run's dispatcher) and, absent
  * this fix, never closed. [[DefaultFlowContext.close]] now also closes such a
  * foreign lead's backend, and [[DefaultFlowContext.agent]] warns loudly the
  * first time it's resolved. Both checks compare backend IDENTITY
  * ([[orca.agents.Agent.backendIdentity]]), not `Agent` reference equality —
  * the positive case below pins that a `copyTool`-derived sibling of a wired
  * agent (the common `_.claude.opus` selector shape) does NOT trip either
  * check, which a naive `Agent eq Agent` implementation would get wrong.
  */
class LeadAgentIdentityTest extends munit.FunSuite:

  private def interaction()(using ox.Ox) = TerminalInteraction.start(
    out = new PrintStream(new ByteArrayOutputStream()),
    useColor = false,
    animated = false
  )

  private def stepRecorder(
      sink: scala.collection.mutable.ListBuffer[String]
  ): OrcaListener =
    case OrcaEvent.Step(msg) => sink += msg
    case _                   => ()

  test(
    "a foreign-agent selector warns at construction and is closed at flow end"
  ):
    val foreignBackend = new RecordingCloseBackend
    val foreignAgent: PiAgent = new DefaultPiAgent(
      foreignBackend,
      AgentConfig(),
      orca.agents.DefaultPrompts,
      OrcaListener.noop,
      NoopInteraction
    )
    val warnings = scala.collection.mutable.ListBuffer.empty[String]
    supervised:
      flow(
        args = OrcaArgs(),
        agent = (_: FlowContext) => foreignAgent,
        workDir = TempRepo.create(),
        interaction = Some(interaction()),
        extraListeners = List(stepRecorder(warnings))
      ):
        ()
    assert(
      warnings.exists(
        _.contains(
          "lead agent was not built from this flow's context"
        )
      ),
      s"expected a foreign-lead construction warning Step, saw: $warnings"
    )
    assertEquals(
      foreignBackend.closeCount,
      1,
      "close() must close a foreign lead's backend so it doesn't leak"
    )

  test(
    "a copyTool-derived sibling of the wired pi agent triggers no warning " +
      "and its backend is closed exactly once"
  ):
    val piBackend = new RecordingCloseBackend
    val warnings = scala.collection.mutable.ListBuffer.empty[String]
    supervised:
      flow(
        args = OrcaArgs(),
        // Mirrors the common `_.claude.opus` selector shape: `.withName` is a
        // `copyTool`-derived sibling — a DIFFERENT `Agent` instance sharing the
        // SAME backend as the wired `pi`, not the wired `pi` value itself.
        agent = (ctx: FlowContext) => ctx.pi.withName("lead-pi"),
        workDir = TempRepo.create(),
        pi = Some(w =>
          new DefaultPiAgent(
            piBackend,
            AgentConfig(),
            w.prompts,
            w.events,
            w.interaction
          )
        ),
        interaction = Some(interaction()),
        extraListeners = List(stepRecorder(warnings))
      ):
        ()
    assert(
      !warnings.exists(
        _.contains("lead agent was not built from this flow's context")
      ),
      s"a copyTool-derived sibling of a wired agent must not trigger the " +
        s"foreign-lead warning, saw: $warnings"
    )
    assertEquals(
      piBackend.closeCount,
      1,
      "the shared backend must be closed exactly once, not once via the " +
        "wired pi and again via the lead"
    )

  private object NoopInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    ): AgentResult[B] =
      throw new UnsupportedOperationException

  /** A minimal `AgentBackend` whose only job is to count `close()` calls —
    * `runAutonomous`/`runInteractive` are never exercised by these tests (the
    * lead agent is never actually called, only resolved and closed).
    */
  private class RecordingCloseBackend extends AgentBackend[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    var closeCount: Int = 0
    override def close(): Unit = closeCount += 1
    def runAutonomous(
        prompt: String,
        session: orca.agents.SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    def runInteractive(
        prompt: String,
        session: orca.agents.SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.Ephemeral(new SessionRegistry.ClaimedOnce)
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
      Enforcement.Ignored
