package orca.runner

import orca.ReportedFailure
import orca.testkit.{ScriptedBackend, TestAgent}
import orca.{AgentSet, OrcaArgs, StackSettings, flow, runFlow}
import orca.agents.{
  Agent,
  AgentConfig,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  OpencodeAgent,
  PiAgent
}
import orca.backend.{AgentResult, TurnRequest}
import orca.events.{OrcaEvent, OrcaListener}
import orca.testkit.GitRepo
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference

/** Pins the foreign-agent handling: a per-role override (`codingAgent =
  * Some(...)`) can return an agent built from a backend that isn't wired into
  * this run — event-blind (built against its own `AgentWiring`, not this run's
  * dispatcher). `runFlow` closes all five wired agents and every foreign role
  * agent when the run ends, so a foreign role agent's backend is closed too.
  * `runFlow` separately warns per role when it resolves a foreign agent,
  * comparing backends ([[orca.agents.Agent.sharesBackendWith]]), not `Agent`
  * reference equality — the positive case below pins that a builder-derived
  * sibling of a wired agent (the common `_.claude.opus` shape) does NOT trip
  * that warning.
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
    "a foreign-agent selector warns at lead resolution and is closed at flow end"
  ):
    val foreignBackend = new UnrunBackend
    val foreignAgent: PiAgent = TestAgent(foreignBackend)
    val warnings = scala.collection.mutable.ListBuffer.empty[String]
    supervised:
      flow(
        args = OrcaArgs(),
        stackSettings = Some(StackSettings.empty),
        codingAgent = Some((_: AgentSet) => foreignAgent),
        workDir = GitRepo.seeded(),
        interaction = Some(interaction()),
        extraListeners = List(stepRecorder(warnings))
      ):
        ()
    assert(
      warnings.exists(
        _.contains(
          "coding agent was not built from this flow's context"
        )
      ),
      s"expected a foreign-lead resolution warning Step, saw: $warnings"
    )
    assert(foreignBackend.isClosed, "a foreign lead's backend must be closed")

  test(
    "a builder-derived sibling of the wired pi agent triggers no warning"
  ):
    val piBackend = new UnrunBackend
    val warnings = scala.collection.mutable.ListBuffer.empty[String]
    supervised:
      flow(
        args = OrcaArgs(),
        stackSettings = Some(StackSettings.empty),
        // Mirrors the common `_.claude.opus` selector shape: `.withName` is a
        // builder-derived sibling — a DIFFERENT `Agent` instance sharing the
        // SAME backend as the wired `pi`, not the wired `pi` value itself.
        codingAgent = Some((agents: AgentSet) => agents.pi.withName("lead-pi")),
        workDir = GitRepo.seeded(),
        pi = Some(w =>
          Agent(
            piBackend,
            AgentConfig(),
            w.prompts,
            w.events,
            w.interaction,
            defaultName = "pi"
          )
        ),
        interaction = Some(interaction()),
        extraListeners = List(stepRecorder(warnings))
      ):
        ()
    assert(
      !warnings.exists(
        _.contains("coding agent was not built from this flow's context")
      ),
      s"a builder-derived sibling of a wired agent must not trigger the " +
        s"foreign-lead warning, saw: $warnings"
    )

  test("a wired agent's backend is closed when the flow completes"):
    val piBackend = new UnrunBackend
    supervised:
      flow(
        args = OrcaArgs(),
        stackSettings = Some(StackSettings.empty),
        workDir = GitRepo.seeded(),
        pi = Some(w =>
          Agent(
            piBackend,
            AgentConfig(),
            w.prompts,
            w.events,
            w.interaction,
            defaultName = "pi"
          )
        ),
        interaction = Some(interaction())
      ):
        ()
    assert(piBackend.isClosed)

  test(
    "a selector that always throws: the original failure is reported once, " +
      "and all five wired backends are still closed"
  ):
    // The selector resolves pre-context, against the wired agent set, inside
    // `runFlow`'s pre-context `surfaced` bracket: its failure is reported as
    // exactly one Error and escapes as `ReportedFailure(boom)`. The
    // context is never constructed, yet the five wired agents must be closed.
    val boom = new RuntimeException("selector always throws")
    val selector: AgentSet => orca.agents.Agent[BackendTag.ClaudeCode.type] =
      _ => throw boom
    val agents = new RecordingAgents
    val listener = new RecordingListener
    val thrown = intercept[ReportedFailure]:
      supervised:
        runFlow(
          FlowHarness.request(
            args = OrcaArgs(),
            stackSettings = Some(StackSettings.empty),
            codingAgent = Some(selector),
            workDir = GitRepo.seeded(),
            interaction = Some(interaction()),
            extraListeners = List(listener),
            branchNaming = None,
            wiring = FlowWiring(
              claude = Some(_ => agents.claude),
              codex = Some(_ => agents.codex),
              opencode = Some(_ => agents.opencode),
              pi = Some(_ => agents.pi),
              gemini = Some(_ => agents.gemini)
            )
          )
        ):
          ()
    assertEquals(
      thrown.cause,
      boom,
      "the flow-level failure must be the selector's original error"
    )
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(
      errors.size,
      1,
      s"the selector's failure must be reported exactly once, saw: $errors"
    )
    for tag <- List(
        BackendTag.ClaudeCode,
        BackendTag.Codex,
        BackendTag.Opencode,
        BackendTag.Pi,
        BackendTag.Gemini
      )
    do
      assert(
        agents.closed(tag),
        s"runFlow must still close the wired $tag backend " +
          s"despite the selector throwing"
      )

  test(
    "a foreign planning override resolves but a later coding override throws: " +
      "the earlier foreign planning backend is still closed"
  ):
    // Planning resolves to a FOREIGN agent (a separate backend, not one of the
    // five wired); coding's override then throws, so `resolveAll` never returns
    // a `RoleResolution`. The foreign planning agent's close, registered as
    // that role resolved, must still run.
    val boom = new RuntimeException("coding selector always throws")
    val foreignBackend = new UnrunBackend
    val foreignPlanning: PiAgent = TestAgent(foreignBackend)
    val agents = new RecordingAgents
    val thrown = intercept[ReportedFailure]:
      supervised:
        runFlow(
          FlowHarness.request(
            args = OrcaArgs(),
            stackSettings = Some(StackSettings.empty),
            planningAgent = Some((_: AgentSet) => foreignPlanning),
            codingAgent = Some((_: AgentSet) => throw boom),
            workDir = GitRepo.seeded(),
            interaction = Some(interaction()),
            extraListeners = Nil,
            branchNaming = None,
            wiring = FlowWiring(
              claude = Some(_ => agents.claude),
              codex = Some(_ => agents.codex),
              opencode = Some(_ => agents.opencode),
              pi = Some(_ => agents.pi),
              gemini = Some(_ => agents.gemini)
            )
          )
        ):
          ()
    assertEquals(
      thrown.cause,
      boom,
      "the flow-level failure must be the coding selector's original error"
    )
    assert(
      foreignBackend.isClosed,
      "an earlier role's foreign backend must still be closed when a LATER " +
        "override throws before `resolveAll` returns"
    )

  /** A backend whose agent is only resolved and closed, never run. */
  private class UnrunBackend extends ScriptedBackend(BackendTag.Pi):
    protected def reply(
        turn: TurnRequest[BackendTag.Pi.type]
    ): AgentResult[BackendTag.Pi.type] =
      throw new UnsupportedOperationException

  /** One agent per wired backend, never run — the selector throws before setup
    * or the body could use one — so a test can check which backends were
    * closed.
    */
  private class RecordingAgents:
    private val claudeBackend = ScriptedBackend.unused(BackendTag.ClaudeCode)
    private val codexBackend = ScriptedBackend.unused(BackendTag.Codex)
    private val opencodeBackend = ScriptedBackend.unused(BackendTag.Opencode)
    private val piBackend = ScriptedBackend.unused(BackendTag.Pi)
    private val geminiBackend = ScriptedBackend.unused(BackendTag.Gemini)

    val claude: ClaudeAgent = TestAgent(claudeBackend)
    val codex: CodexAgent = TestAgent(codexBackend)
    val opencode: OpencodeAgent = TestAgent(opencodeBackend)
    val pi: PiAgent = TestAgent(piBackend)
    val gemini: GeminiAgent = TestAgent(geminiBackend)

    def closed(tag: BackendTag): Boolean = tag match
      case BackendTag.ClaudeCode => claudeBackend.isClosed
      case BackendTag.Codex      => codexBackend.isClosed
      case BackendTag.Opencode   => opencodeBackend.isClosed
      case BackendTag.Pi         => piBackend.isClosed
      case BackendTag.Gemini     => geminiBackend.isClosed

  private class RecordingListener extends OrcaListener:
    private val seen = new AtomicReference[List[OrcaEvent]](Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def events: List[OrcaEvent] = seen.get().reverse
