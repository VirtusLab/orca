package orca.runner

import orca.{AgentSet, OrcaArgs, StackSettings, flow, runFlow}
import orca.agents.{
  AgentConfig,
  Announce,
  AutoApprove,
  AutonomousTextCall,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  Enforcement,
  AgentCall,
  GeminiAgent,
  JsonData,
  Model,
  OpencodeAgent,
  PiAgent,
  ToolSet
}
import orca.backend.{
  AgentBackend,
  AgentResult,
  Conversation,
  Interaction,
  IdScheme,
  SessionSupport
}
import orca.events.{OrcaEvent, OrcaListener}
import orca.tools.pi.DefaultPiAgent
import orca.testkit.GitRepo
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference

/** Pins the foreign-agent handling: a per-role override (`codingAgent =
  * Some(...)`) can return an agent built from a backend that isn't wired into
  * this run — event-blind (built against its own `AgentWiring`, not this run's
  * dispatcher) and, absent this fix, never closed. [[DefaultFlowContext.close]]
  * unconditionally closes the three resolved role agents alongside all five
  * wired agents (no wired/foreign branch needed there — a backend's own
  * `close()` is idempotent), so a foreign role agent's backend is closed too.
  * `runFlow` separately warns loudly per role when it resolves a foreign agent
  * against the wired agent set, comparing backend IDENTITY
  * ([[orca.agents.Agent.backendIdentity]]), not `Agent` reference equality —
  * the positive case below pins that a `copyTool`-derived sibling of a wired
  * agent (the common `_.claude.opus` shape) does NOT trip that warning, which a
  * naive `Agent eq Agent` implementation would get wrong, and that its shared
  * backend's teardown still runs exactly once despite being closed via two
  * different `Agent` instances.
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
        stackSettings = Some(StackSettings.empty),
        // Mirrors the common `_.claude.opus` selector shape: `.withName` is a
        // `copyTool`-derived sibling — a DIFFERENT `Agent` instance sharing the
        // SAME backend as the wired `pi`, not the wired `pi` value itself.
        codingAgent = Some((agents: AgentSet) => agents.pi.withName("lead-pi")),
        workDir = GitRepo.seeded(),
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
        _.contains("coding agent was not built from this flow's context")
      ),
      s"a copyTool-derived sibling of a wired agent must not trigger the " +
        s"foreign-lead warning, saw: $warnings"
    )
    assertEquals(
      piBackend.closeCount,
      1,
      "the shared backend's teardown must run exactly once even though " +
        "close() now unconditionally closes both the wired pi and the lead " +
        "sharing its backend — the backend's own idempotence guard (not a " +
        "caller-side wired/foreign check) is what makes that safe"
    )

  test(
    "a selector that always throws: the original failure is reported once, " +
      "and all five wired backends are still closed"
  ):
    // The selector resolves pre-context, against the wired agent set, inside
    // `runFlow`'s pre-context `surfaced` bracket: its failure is reported as
    // exactly one Error and escapes as `SurfacedFlowFailure(boom)`. Since the
    // context is never constructed, no `ctx.close()` runs — the ownership
    // guard in `runFlow` is what must close the five wired agents.
    val boom = new RuntimeException("selector always throws")
    val selector: AgentSet => orca.agents.Agent[BackendTag.ClaudeCode.type] =
      _ => throw boom
    val agents = new RecordingAgents
    val listener = new RecordingListener
    val thrown = intercept[SurfacedFlowFailure]:
      supervised:
        runFlow(
          args = OrcaArgs(),
          stackSettings = Some(StackSettings.empty),
          codingAgent = Some(selector),
          workDir = GitRepo.seeded(),
          interaction = Some(interaction()),
          extraListeners = List(listener),
          branchNaming = None,
          returnToStartBranch = false,
          progressStore = None,
          wiring = FlowWiring(
            claude = Some(_ => agents.claude),
            codex = Some(_ => agents.codex),
            opencode = Some(_ => agents.opencode),
            pi = Some(_ => agents.pi),
            gemini = Some(_ => agents.gemini)
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
      assertEquals(
        agents.closeCounts(tag),
        1,
        s"the ownership guard must still close the wired $tag backend " +
          s"despite the selector throwing"
      )

  private object NoopInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    )(using ox.Ox): AgentResult[B] =
      throw new UnsupportedOperationException

  /** A minimal `AgentBackend` that counts REALISED close teardowns —
    * `runAutonomous`/`runInteractive` are never exercised by these tests (the
    * lead agent is never actually called, only resolved and closed).
    *
    * `close()` itself is CAS-guarded, mirroring the idempotence every real
    * backend already provides (the shared `closedFlag` latches via a plain
    * `set`, opencode's process teardown is CAS-guarded, every other backend's
    * `close()` is a no-op) — see `DefaultFlowContext.close`'s scaladoc. Since
    * `DefaultFlowContext.close()` now appends the resolved lead
    * UNCONDITIONALLY, a lead sharing a wired backend gets `Agent.close()`
    * invoked on it twice; `closeCount` pins that the doubled CALL still
    * produces a single observable teardown, which is the actual contract
    * `close()`'s scaladoc relies on — not that `Agent.close()` is called
    * exactly once.
    */
  private class RecordingCloseBackend extends AgentBackend[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
    var closeCount: Int = 0
    override def close(): Unit =
      if closed.compareAndSet(false, true) then closeCount += 1
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
      SessionSupport.ephemeral(IdScheme.ClientClaimed)
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
      Enforcement.Ignored
    def structuredOutputMode: orca.agents.StructuredOutputMode =
      orca.agents.StructuredOutputMode.RawText

  /** One recording stub per wired backend, each incrementing `closeCounts` for
    * its own tag on `close()`. No test ever drives an `autonomous`/`resultAs`
    * call through these — the selector throws before setup or the body could
    * ever use the lead agent — so those methods are unreachable stubs, same as
    * `NoopOpencode`/`NoopPi`/`NoopGemini` in `DefaultFlowContextTest`.
    */
  private class RecordingAgents:
    private val counts =
      new AtomicReference[Map[BackendTag, Int]](Map.empty.withDefaultValue(0))
    def closeCounts: Map[BackendTag, Int] = counts.get()
    private def recordClose(tag: BackendTag): Unit =
      val _ = counts.updateAndGet(m => m.updated(tag, m(tag) + 1))

    val claude: ClaudeAgent = new ClaudeAgent:
      val name = "recording-claude"
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
      def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
        throw new UnsupportedOperationException
      def resultAs[O: JsonData: Announce]
          : AgentCall[BackendTag.ClaudeCode.type, O] =
        throw new UnsupportedOperationException
      override private[orca] def close(): Unit =
        recordClose(BackendTag.ClaudeCode)

    val codex: CodexAgent = new CodexAgent:
      val name = "recording-codex"
      def mini = this
      def withModel(model: Model) = this
      def withConfig(c: AgentConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withTools(tools: ToolSet) = this
      def autonomous: AutonomousTextCall[BackendTag.Codex.type] =
        throw new UnsupportedOperationException
      def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Codex.type, O] =
        throw new UnsupportedOperationException
      override private[orca] def close(): Unit = recordClose(BackendTag.Codex)

    val opencode: OpencodeAgent = new OpencodeAgent:
      val name = "recording-opencode"
      def anthropicOpus = this
      def anthropicSonnet = this
      def anthropicHaiku = this
      def openaiSol = this
      def openaiTerra = this
      def openaiLuna = this
      def withModel(providerModel: String) = this
      def withConfig(c: AgentConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withTools(tools: ToolSet) = this
      def autonomous: AutonomousTextCall[BackendTag.Opencode.type] =
        throw new UnsupportedOperationException
      def resultAs[O: JsonData: Announce]
          : AgentCall[BackendTag.Opencode.type, O] =
        throw new UnsupportedOperationException
      override private[orca] def close(): Unit =
        recordClose(BackendTag.Opencode)

    val pi: PiAgent = new PiAgent:
      val name = "recording-pi"
      def withModel(model: Model) = this
      def withConfig(c: AgentConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withTools(tools: ToolSet) = this
      def autonomous: AutonomousTextCall[BackendTag.Pi.type] =
        throw new UnsupportedOperationException
      def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Pi.type, O] =
        throw new UnsupportedOperationException
      override private[orca] def close(): Unit = recordClose(BackendTag.Pi)

    val gemini: GeminiAgent = new GeminiAgent:
      val name = "recording-gemini"
      def flash = this
      def withModel(model: Model) = this
      def withConfig(c: AgentConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withTools(tools: ToolSet) = this
      def autonomous: AutonomousTextCall[BackendTag.Gemini.type] =
        throw new UnsupportedOperationException
      def resultAs[O: JsonData: Announce]
          : AgentCall[BackendTag.Gemini.type, O] =
        throw new UnsupportedOperationException
      override private[orca] def close(): Unit = recordClose(BackendTag.Gemini)

  private class RecordingListener extends OrcaListener:
    private val seen = new AtomicReference[List[OrcaEvent]](Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def events: List[OrcaEvent] = seen.get().reverse
