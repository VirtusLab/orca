package orca.agents

import orca.backend.{
  Conversation,
  Interaction,
  AgentBackend,
  AgentResult,
  IdScheme,
  SessionSupport
}
import orca.events.{OrcaEvent, OrcaListener, Usage}

/** Unsupported `resultAs[O]` output shape: OpenAI's strict structured-output
  * mode can't express a Map's unbounded key set. See the "Map field throws...
  * at construction" test below.
  */
case class MapCarrier(m: Map[String, Int]) derives JsonData

class BaseAgentTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  test("close() delegates to the backend"):
    val backend = new RecordingCloseBackend
    val tool = new StubTool(backend)
    tool.close()
    assertEquals(backend.closeCount, 1)

  // A closed agent must fail loud rather than let a leaked handle
  // silently emit to a closed run's dispatcher.
  test("run after close() throws OrcaFlowException"):
    val tool = new StubTool(new RecordingCloseBackend)
    tool.close()
    val thrown = intercept[orca.OrcaFlowException]:
      tool.run("prompt")
    assertEquals(
      thrown.getMessage,
      AgentBackend.ClosedMessage
    )

  // An unsupported output shape (a Map[String, _] field, rejected by
  // JsonSchemaGen) must fail at `resultAs[O]` construction, before any stage
  // runs — not remotely, after `.run()` spawns a backend process.
  test("resultAs[O] with a Map field throws OrcaFlowException at construction"):
    val tool = new StubTool(StubBackend)
    val thrown = intercept[orca.OrcaFlowException]:
      tool.resultAs[MapCarrier]
    assert(
      thrown.getMessage.contains("List of key/value case classes"),
      s"expected actionable Map-field message, got: ${thrown.getMessage}"
    )

  test("resultAs after close() throws OrcaFlowException"):
    val tool = new StubTool(new RecordingCloseBackend)
    tool.close()
    val thrown = intercept[orca.OrcaFlowException]:
      tool.resultAs[String]
    assertEquals(
      thrown.getMessage,
      AgentBackend.ClosedMessage
    )

  // The closed latch lives on the shared backend, so it survives the two ways a
  // leaked handle re-derives a "fresh" object after close: copyTool builders (a
  // new Agent over the same backend) and a resultAs gateway built before close
  // and invoked after.
  test("a copyTool-derived handle after close() throws OrcaFlowException"):
    val tool = new StubTool(new RecordingCloseBackend)
    tool.close()
    val derived = tool.withName("derived")
    val thrown = intercept[orca.OrcaFlowException]:
      derived.run("prompt")
    assertEquals(
      thrown.getMessage,
      AgentBackend.ClosedMessage
    )

  test("a resultAs gateway obtained before close() throws when run after it"):
    val tool = new StubTool(new RecordingCloseBackend)
    val gateway = tool.resultAs[String]
    tool.close()
    val thrown = intercept[orca.OrcaFlowException]:
      gateway.autonomous.run("prompt")
    assertEquals(
      thrown.getMessage,
      AgentBackend.ClosedMessage
    )

  // The runtime's internal cheap turns (branch naming, commit messages) consume
  // their reply and re-surface it as the caller's own Step event; streaming the
  // turn too would print the same text twice.
  test(
    "cheapOneShot suppresses the turn's display events; TokensUsed still flows"
  ):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool = new StubTool(new NoisyBackend, listener = listener)
    val reply = tool.cheapOneShot("name this branch", fallback = "fb")
    assertEquals(reply, "short-label")
    val events = seen.get()
    assert(
      !events.exists(_.isInstanceOf[OrcaEvent.AssistantMessage]),
      s"assistant prose must be suppressed on a quiet turn: $events"
    )
    assert(
      !events.exists(_.isInstanceOf[OrcaEvent.ToolUse]),
      s"tool-use lines must be suppressed on a quiet turn: $events"
    )
    assert(
      !events.exists(_.isInstanceOf[OrcaEvent.UserPrompt]),
      s"the prompt echo must be suppressed on a quiet turn: $events"
    )
    assert(
      events.exists(_.isInstanceOf[OrcaEvent.TokensUsed]),
      s"cost accounting must still flow on a quiet turn: $events"
    )

  // The manifest writer (ADR 0021 §8) needs the wire id known after the
  // backend call returns, so `SessionCommitted` fires post-`runAutonomous`
  // with whatever that call just committed.
  test("autonomous run emits exactly one SessionCommitted with the stub's wire id"):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool = new StubTool(new CommittingBackend("wire-committed"), listener = listener)
    val _ = tool.run("prompt")
    val committed = seen.get().collect { case e: OrcaEvent.SessionCommitted =>
      e
    }
    assertEquals(committed.size, 1, committed)
    assertEquals(committed.head.backend, BackendTag.Pi.wireName)
    assertEquals(committed.head.wireId, Some("wire-committed"))
    assertEquals(committed.head.agent, "stub")
    assertEquals(committed.head.role, None)

  // `quietTextTurn` runs `backend.runAutonomous` directly on a fresh session,
  // bypassing `runWithSession` entirely — it must never surface a session to
  // the manifest writer.
  test("quietTextTurn emits no SessionCommitted"):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool = new StubTool(new CommittingBackend("wire-quiet"), listener = listener)
    val _ = tool.quietTextTurn("internal prompt")
    assert(
      !seen.get().exists(_.isInstanceOf[OrcaEvent.SessionCommitted]),
      s"quietTextTurn must not emit SessionCommitted: ${seen.get()}"
    )

  // An explicit `Some(...)` config wholly replaces the tool-level config (no
  // per-field merge); omission (`None`) inherits it — see
  // `BaseAgent.effectiveConfig`.
  test(
    "run(config = Some(AgentConfig())) wholly replaces the tool-level config"
  ):
    val backend = new RecordingConfigBackend
    val toolConfig = AgentConfig(
      model = Some(Model("tool-level-model")),
      systemPrompt = Some("tool-level-prompt")
    )
    val tool = new StubTool(backend, toolConfig)
    val _ = tool.run("prompt", config = Some(AgentConfig()))
    assertEquals(
      backend.lastConfig,
      Some(AgentConfig()),
      "an explicit Some(...) must wipe the tool-level config, not merge with it"
    )

  test("run() with config omitted falls back to the tool-level config"):
    val backend = new RecordingConfigBackend
    val toolConfig = AgentConfig(
      model = Some(Model("tool-level-model")),
      systemPrompt = Some("tool-level-prompt")
    )
    val tool = new StubTool(backend, toolConfig)
    val _ = tool.run("prompt")
    assertEquals(backend.lastConfig, Some(toolConfig))

  private class StubTool(
      backend: AgentBackend[BackendTag.Pi.type],
      toolConfig: AgentConfig = AgentConfig(),
      listener: OrcaListener = OrcaListener.noop
  ) extends BaseAgent[BackendTag.Pi.type, Agent[BackendTag.Pi.type]](
        backend,
        toolConfig,
        StubPrompts,
        listener,
        StubInteraction
      ):
    val name: String = "stub"
    // A new instance over the same backend, as production implementations do, so
    // the copyTool-after-close test exercises the real leak shape rather than a
    // same-instance alias.
    protected def copyTool(
        config: AgentConfig = toolConfig,
        name: String = name,
        role: Option[String] = None
    ): Agent[BackendTag.Pi.type] = new StubTool(backend, config, listener)

  /** Records the `AgentConfig` the framework actually resolved and passed to
    * the backend, so tests can assert on it directly.
    */
  private class RecordingConfigBackend extends AgentBackend[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    var lastConfig: Option[AgentConfig] = None
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      lastConfig = Some(config)
      AgentResult(
        WireSessionId[BackendTag.Pi.type]("server-wire-id"),
        "out",
        Usage.empty
      )
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
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
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  /** Emits the streaming display events a real drain would (a tool line and the
    * assistant's reply) so the quiet-turn test can assert they are filtered.
    */
  private class NoisyBackend extends AgentBackend[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      events.onEvent(OrcaEvent.ToolUse("Read", "{}"))
      events.onEvent(OrcaEvent.AssistantMessage("short-label"))
      AgentResult(
        WireSessionId[BackendTag.Pi.type]("wire"),
        "short-label",
        Usage.empty
      )
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
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
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  /** Mimics a real subprocess backend's `drainAndCommit`: returns a canned
    * result and immediately commits the session as resumable, so
    * `Agent.resumeWireId` reports `wireId` once `runAutonomous` returns.
    */
  private class CommittingBackend(wireId: String)
      extends AgentBackend[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.durable(IdScheme.ServerMinted, _ => true)
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      val result = AgentResult(
        WireSessionId[BackendTag.Pi.type](wireId),
        "out",
        Usage.empty
      )
      sessions.commitAfterDrain(session, result.wireId)
      result
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
      Enforcement.Ignored
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  private object StubBackend extends AgentBackend[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      AgentResult(
        WireSessionId[BackendTag.Pi.type]("server-wire-id"),
        "out",
        Usage.empty
      )
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
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
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  private class RecordingCloseBackend extends AgentBackend[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    var closeCount: Int = 0
    override def close(): Unit = closeCount += 1
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] = ???
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] = ???
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.ephemeral(IdScheme.ClientClaimed)
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
      Enforcement.Ignored
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  private object StubPrompts extends Prompts:
    def autonomous(
        input: String,
        outputSchema: String,
        config: AgentConfig,
        mode: StructuredOutputMode
    ): String = ???
    def interactive(
        input: String,
        outputSchema: String,
        config: AgentConfig
    ): String = ???
    def retry(failedResponse: String, parseError: String): String = ???

  private object StubInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](conversation: Conversation[B])(using
        ox.Ox
    ): AgentResult[B] =
      ???
