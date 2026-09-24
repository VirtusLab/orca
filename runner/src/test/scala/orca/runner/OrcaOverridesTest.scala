package orca.runner

import orca.{FlowContext, OrcaArgs, StackSettings, flow, fs, pi}
import orca.tools.{FsTool}
import orca.testkit.{GitRepo, ScriptedBackend, TestAgent}
import orca.agents.{BackendTag, ClaudeAgent, Model, OpencodeAgent, PiAgent}
import orca.events.{CostTracker, OrcaEvent, OrcaListener}
import orca.testkit.Usages.usage
import orca.tools.opencode.OpencodeAgents
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

class OrcaOverridesTest extends munit.FunSuite:

  // These tests drive gated LLM calls directly in the flow body (not inside a
  // `stage`), so mint the in-stage token for the suite (package `orca.runner`).
  private given orca.InStage = orca.InStage.unsafe

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
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = GitRepo.seeded(),
        fs = Some(fake),
        interaction = Some(interaction)
      ):
        observed = fs.read("ignored")
    assertEquals(observed, Some("canned content"))

  test("flow uses a custom ClaudeAgent when supplied"):
    val fakeClaude: ClaudeAgent = TestAgent(
      ScriptedBackend.replying(BackendTag.ClaudeCode)(t =>
        s"echo: ${t.prompt}"
      ),
      "fake"
    )
    var observed: String = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        stackSettings = Some(StackSettings.empty),
        workDir = GitRepo.seeded(),
        claude = Some(_ => fakeClaude),
        interaction = Some(interaction)
      ):
        observed = summon[FlowContext].claude.run("hi")
    assertEquals(observed, "echo: hi")

  test("flow uses a custom OpencodeAgent when supplied"):
    val fakeOpencode: OpencodeAgent = TestAgent(
      ScriptedBackend.replying(BackendTag.Opencode)(t =>
        s"opencode: ${t.prompt}"
      ),
      "fake"
    )
    var observed: String = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = GitRepo.seeded(),
        opencode = Some(_ => fakeOpencode),
        interaction = Some(interaction)
      ):
        observed = summon[FlowContext].opencode.run("hi")
    assertEquals(observed, "opencode: hi")

  test(
    "the opencode override slot accepts its own default factory (Ox ?=> result)"
  ):
    // The opencode param is `AgentWiring => Ox ?=> OpencodeAgent`, so
    // `Some(w => OpencodeAgents.default(w))` — a factory that itself needs an Ox
    // — compiles at the `flow(...)` argument position, with the Ox resolved
    // where `WiredAgents.build` applies it. Compiles-and-runs: the lead is the
    // claude stub, the body never touches opencode, and opencode's `serve` spawn
    // is lazy, so the flow completes without a real process.
    var ran = false
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = GitRepo.seeded(),
        opencode = Some(w => OpencodeAgents.default(w)),
        interaction = Some(interaction)
      ):
        ran = true
    assert(ran, "flow with an opencode default-factory override must run")

  test("flow uses a custom PiAgent when supplied"):
    val fakePi: PiAgent = TestAgent(
      ScriptedBackend.replying(BackendTag.Pi)(t => s"pi: ${t.prompt}"),
      "fake-pi"
    )
    var observed: String = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = GitRepo.seeded(),
        pi = Some(_ => fakePi),
        interaction = Some(interaction)
      ):
        observed = pi.run("hi")
    assertEquals(observed, "pi: hi")

  test(
    "an agent-override factory receives the run's event sink: its spend reaches extraListeners"
  ):
    // A user agent built by the override factory must land on the SAME
    // dispatcher as the defaults, so the tokens it spends reach the cost tracker
    // and terminal. The factory receives `w.events`, which the agent's `run`
    // reports its spend through.
    def wiredClaude(events: OrcaListener): ClaudeAgent = TestAgent(
      ScriptedBackend.replying(BackendTag.ClaudeCode)(t => s"ok: ${t.prompt}"),
      "wired",
      events = events
    )
    // Records which agents' TokensUsed reach a run listener — the override
    // agent's "wired" event must be among them.
    var seen: List[String] = Nil
    val recorder: OrcaListener =
      case t: OrcaEvent.TokensUsed => seen = t.spend.agent :: seen
      case _                       => ()
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        stackSettings = Some(StackSettings.empty),
        workDir = GitRepo.seeded(),
        claude = Some(w => wiredClaude(w.events)),
        interaction = Some(interaction),
        extraListeners = List(recorder)
      ):
        val _ = summon[FlowContext].claude.run("hi")
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
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = GitRepo.seeded(),
        interaction = Some(interaction),
        extraListeners = List(tracker)
      ):
        summon[FlowContext].emit(
          OrcaEvent.UnpricedTurn(
            "test-agent",
            // A model the shipped table prices, so the cost below is the run's
            // own resolution rather than an absent figure.
            Some(Model("claude-haiku-4-5")),
            usage(10L, 5L),
            role = None,
            turn = 1,
            conversationKey = None
          )
        )
    // TerminalInteraction ignores TokensUsed; CostTracker should accumulate.
    assertEquals(tracker.total, usage(10L, 5L))
    // The tracker prices nothing itself: a cost here means the run resolved it
    // on the way into the fan-out.
    assert(tracker.totalCost.nonEmpty, tracker.summary)
