package orca.runner

import orca.{FlowContext, OrcaArgs, flow, fs, pi}
import orca.tools.{FsTool}
import orca.agents.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  ClaudeAgent,
  JsonData,
  PiAgent,
  AgentCall,
  AgentConfig,
  Model,
  OpencodeAgent,
  SessionId,
  ToolSet
}
import orca.events.{CostTracker, OrcaEvent, OrcaListener, Usage}
import orca.tools.opencode.OpencodeAgents
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

class OrcaOverridesTest extends munit.FunSuite:

  // These tests drive gated LLM calls directly in the flow body (not inside a
  // `stage`), so mint the in-stage token for the suite (package `orca.runner`).
  private given orca.InStage = orca.InStage.unsafe

  // The leading-model selector defaults to `_.claude` (ADR 0018 §2.5); these
  // tests assert tool-override wiring, not LLM behaviour, so they resolve a stub
  // via a `_ => StubAgent.claude` selector.
  private val stubLead: FlowContext => ClaudeAgent = _ => StubAgent.claude

  test("flow uses a custom FsTool when supplied"):
    val fake = new FsTool:
      def read(path: String): Option[String] = Some("canned content")
      def write(path: String, content: String)(using
          orca.WorkspaceWrite
      ): Unit =
        ()
      def list(glob: String): List[String] = List("custom")
    var observed: Option[String] = None
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = stubLead,
        workDir = TempRepo.create(),
        fs = Some(fake),
        interaction = Some(interaction)
      ):
        observed = fs.read("ignored")
    assertEquals(observed, Some("canned content"))

  test("flow uses a custom ClaudeAgent when supplied"):
    val fakeClaude = new ClaudeAgent:
      val name = "fake"
      def haiku = this
      def sonnet = this
      def opus = this
      def fable = this
      def withModel(model: Model) = this
      def withNetworkTools(t: Seq[String]) = this
      def withConfig(c: AgentConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withTools(tools: ToolSet) = this
      val autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
        new AutonomousTextCall[BackendTag.ClaudeCode.type]:
          def run(
              p: String,
              session: SessionId[BackendTag.ClaudeCode.type],
              c: Option[AgentConfig],
              emitPrompt: Boolean
          )(using
              orca.InStage
          ): (SessionId[BackendTag.ClaudeCode.type], String) =
            (SessionId[BackendTag.ClaudeCode.type]("fake-sid"), s"echo: $p")
      def resultAs[O: JsonData: Announce]
          : AgentCall[BackendTag.ClaudeCode.type, O] =
        ???
    var observed: String = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = _ => fakeClaude,
        workDir = TempRepo.create(),
        claude = Some(_ => fakeClaude),
        interaction = Some(interaction)
      ):
        observed = summon[FlowContext].claude.autonomous.run("hi")._2
    assertEquals(observed, "echo: hi")

  test("flow uses a custom OpencodeAgent when supplied"):
    val fakeOpencode = new OpencodeAgent:
      val name = "fake"
      def anthropicOpus = this
      def anthropicSonnet = this
      def anthropicHaiku = this
      def openaiGpt5 = this
      def openaiGpt5Codex = this
      def openaiGpt5Mini = this
      def withModel(providerModel: String) = this
      def withConfig(c: AgentConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withTools(tools: ToolSet) = this
      val autonomous: AutonomousTextCall[BackendTag.Opencode.type] =
        new AutonomousTextCall[BackendTag.Opencode.type]:
          def run(
              p: String,
              session: SessionId[BackendTag.Opencode.type],
              c: Option[AgentConfig],
              emitPrompt: Boolean
          )(using orca.InStage): (SessionId[BackendTag.Opencode.type], String) =
            (SessionId[BackendTag.Opencode.type]("fake-sid"), s"opencode: $p")
      def resultAs[O: JsonData: Announce]
          : AgentCall[BackendTag.Opencode.type, O] = ???
    var observed: String = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = stubLead,
        workDir = TempRepo.create(),
        opencode = Some(_ => fakeOpencode),
        interaction = Some(interaction)
      ):
        observed = summon[FlowContext].opencode.autonomous.run("hi")._2
    assertEquals(observed, "opencode: hi")

  test(
    "the opencode override slot accepts its own default factory (Ox ?=> result)"
  ):
    // Item 2 pin: the opencode param is `AgentWiring => Ox ?=> OpencodeAgent`,
    // so `Some(w => OpencodeAgents.default(w))` — the factory that itself needs
    // an Ox — compiles at the `flow(...)` argument position, with the Ox
    // resolved where `withDefaults` applies it. This is a compiles-and-runs
    // case (not a stub-CLI run): the lead is the claude stub and the body never
    // touches opencode, and the opencode `serve` spawn is lazy, so the flow
    // runs to completion without a real process. Building the default opencode
    // agent via the factory is what's under test.
    var ran = false
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = stubLead,
        workDir = TempRepo.create(),
        opencode = Some(w => OpencodeAgents.default(w)),
        interaction = Some(interaction)
      ):
        ran = true
    assert(ran, "flow with an opencode default-factory override must run")

  test("flow uses a custom PiAgent when supplied"):
    val fakePi = new PiAgent:
      val name = "fake-pi"
      def withModel(model: Model) = this
      def withConfig(c: AgentConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withTools(tools: ToolSet) = this
      val autonomous: AutonomousTextCall[BackendTag.Pi.type] =
        new AutonomousTextCall[BackendTag.Pi.type]:
          def run(
              p: String,
              session: SessionId[BackendTag.Pi.type],
              c: Option[AgentConfig],
              emitPrompt: Boolean
          )(using orca.InStage): (SessionId[BackendTag.Pi.type], String) =
            (SessionId[BackendTag.Pi.type]("fake-pi-sid"), s"pi: $p")
      def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Pi.type, O] =
        ???
    var observed: String = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = stubLead,
        workDir = TempRepo.create(),
        pi = Some(_ => fakePi),
        interaction = Some(interaction)
      ):
        observed = pi.autonomous.run("hi")._2
    assertEquals(observed, "pi: hi")

  test(
    "an agent-override factory receives the run's event sink (7.8): its TokensUsed reaches extraListeners"
  ):
    // The pin for complexity-review 7.8: a user agent built by the override
    // factory must land on the SAME dispatcher as the defaults, so the tokens
    // it spends reach the cost tracker and terminal. The factory receives
    // `w.events`; the stub emits a TokensUsed through it on `run`. The old
    // `Option[Agent]` overrides were built before the dispatcher existed and
    // could never do this.
    def wiredClaude(events: OrcaListener): ClaudeAgent = new ClaudeAgent:
      val name = "wired"
      def haiku = this
      def sonnet = this
      def opus = this
      def fable = this
      def withModel(model: Model) = this
      def withNetworkTools(t: Seq[String]) = this
      def withConfig(c: AgentConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withTools(tools: ToolSet) = this
      val autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
        new AutonomousTextCall[BackendTag.ClaudeCode.type]:
          def run(
              p: String,
              session: SessionId[BackendTag.ClaudeCode.type],
              c: Option[AgentConfig],
              emitPrompt: Boolean
          )(using
              orca.InStage
          ): (SessionId[BackendTag.ClaudeCode.type], String) =
            events.onEvent(
              OrcaEvent.TokensUsed(
                "wired",
                Some(Model("wired-model")),
                Usage(7L, 3L, None)
              )
            )
            (SessionId[BackendTag.ClaudeCode.type]("wired-sid"), s"ok: $p")
      def resultAs[O: JsonData: Announce]
          : AgentCall[BackendTag.ClaudeCode.type, O] = ???
    // Records which agents' TokensUsed reach a run listener — the override
    // agent's "wired" event must be among them.
    var seen: List[String] = Nil
    val recorder: OrcaListener =
      case OrcaEvent.TokensUsed(agent, _, _) => seen = agent :: seen
      case _                                 => ()
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = _.claude,
        workDir = TempRepo.create(),
        claude = Some(w => wiredClaude(w.events)),
        interaction = Some(interaction),
        extraListeners = List(recorder)
      ):
        val _ = summon[FlowContext].claude.autonomous.run("hi")
    assert(
      seen.contains("wired"),
      s"override's TokensUsed never reached the run listener; saw $seen"
    )

  test("flow collects extra listeners alongside the interaction's"):
    val buf = new ByteArrayOutputStream()
    val tracker = new CostTracker
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(buf),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = stubLead,
        workDir = TempRepo.create(),
        interaction = Some(interaction),
        extraListeners = List(tracker)
      ):
        summon[FlowContext].emit(
          OrcaEvent.TokensUsed(
            "test-agent",
            Some(Model("test-model")),
            Usage(10L, 5L, None)
          )
        )
    // TerminalInteraction ignores TokensUsed; CostTracker should accumulate.
    assertEquals(tracker.total, Usage(10L, 5L, None))
