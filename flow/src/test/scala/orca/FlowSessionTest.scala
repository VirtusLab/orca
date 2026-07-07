package orca

import munit.FunSuite
import orca.backend.{
  Conversation,
  Interaction,
  AgentBackend,
  AgentResult,
  SessionRegistry,
  SessionSupport
}
import orca.events.OrcaListener
import orca.agents.{
  Announce,
  AgentInput,
  AutonomousAgentCall,
  AutonomousTextCall,
  InteractiveAgentCall,
  BackendTag,
  JsonData,
  AgentCall,
  AgentConfig,
  Agent,
  Prompts,
  SessionId,
  WireSessionId,
  ToolSet,
  BaseAgent
}
import orca.progress.{ProgressHeader, ProgressStore, StageEntry, SessionRecord}
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString

/** Tests for [[FlowSession]] — the durable-session handle that owns the probe →
  * seed/preamble → run → persist protocol (ADR 0018 §2.6, absorbs the former
  * `agent.runSeeded` extension).
  *
  * Each scenario constructs a [[FlowSession]] directly over a
  * [[StubAgentForSeeded]] (whose `sessionExists` and `run` behaviours are
  * injected at construction time) and a fixed [[testSession]] id, and asserts
  * on `capturedPrompt` (what the prompt looked like after preamble/seed
  * composition) and on the persisted [[SessionRecord]].
  */
class FlowSessionTest extends FunSuite:

  // `FlowSession.run`/`resultAs` are gated on `InStage` + `WorkspaceWrite`
  // (the explicit mutation token, per ADR 0018 §6); mint both for the suite.
  private given orca.InStage = orca.InStage.unsafe
  private given orca.WorkspaceWrite = orca.WorkspaceWrite.unsafe

  /** Builds the durability capability the stubs expose through
    * `sessionSupport`, seeding the production
    * [[SessionRegistry.ClientToServer]] via `commitSuccess` rather than
    * hand-rolling a `SessionRegistry`. Both `sessionExists` and `resumeWireId`
    * route through it (the trio is now `final` on [[Agent]], and existence is
    * registry-gated). A safe placeholder wire id is committed when `exists` is
    * wanted so the probe (which returns `exists`) runs; otherwise the
    * caller-supplied `learnedWireId`, if any, is committed, which is what
    * `resumeWireId` surfaces for persistence.
    */
  private def stubSupport(
      exists: Boolean,
      learnedWireId: Option[String] = None
  ): SessionSupport[BackendTag.ClaudeCode.type] =
    val reg = new SessionRegistry.ClientToServer[BackendTag.ClaudeCode.type]
    (if exists then Some("live-session-wire") else learnedWireId)
      .foreach(w =>
        reg.commitSuccess(
          testSession,
          WireSessionId[BackendTag.ClaudeCode.type](w)
        )
      )
    SessionSupport.Durable(reg, _ => exists)

  /** A fixed session id used across all tests; avoids UUID randomness in
    * assertions and lets `makeControl` pre-populate the log without forward
    * references.
    */
  private val testSessionId = "test-session-uuid-1234"
  private val testSession: SessionId[BackendTag.ClaudeCode.type] =
    SessionId[BackendTag.ClaudeCode.type](testSessionId)

  /** A structured result type for exercising the `resultAs[O]` durable door. */
  private case class StubResult(v: String) derives JsonData

  /** Controllable Agent stub for seeded-run tests.
    *
    * @param existsResult
    *   The value `sessionExists` returns — set `true` to exercise the "live
    *   session" branch, `false` to exercise the re-seed path.
    * @param runResult
    *   The text `autonomous.run` echoes back.
    */
  private class StubAgentForSeeded(
      existsResult: Boolean,
      runResult: String = "ok",
      learnedWireId: Option[String] = None
  ) extends Agent[BackendTag.ClaudeCode.type]:
    val name: String = "stub-seeded"

    private var _capturedPrompt: Option[String] = None

    /** The prompt the stub's `run` (free-text or structured) actually received,
      * after preamble/seed composition.
      */
    def capturedPrompt: Option[String] = _capturedPrompt

    /** Drives both `sessionExists` (via the registry-gated probe) and
      * `resumeWireId` (via the registry's `resumeWireId`) — `learnedWireId`
      * mirrors a server-id backend's persist path, `None` mirrors pi.
      */
    override private[orca] def sessionSupport
        : Option[SessionSupport[BackendTag.ClaudeCode.type]] =
      Some(stubSupport(existsResult, learnedWireId))

    val autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
      new AutonomousTextCall[BackendTag.ClaudeCode.type]:
        def run(
            prompt: String,
            session: SessionId[BackendTag.ClaudeCode.type],
            config: Option[AgentConfig],
            emitPrompt: Boolean
        )(using orca.InStage): (SessionId[BackendTag.ClaudeCode.type], String) =
          _capturedPrompt = Some(prompt)
          (session, runResult)

    /** Structured door stub: captures the serialized input (after preamble/seed
      * composition) and decodes a fixed `{"v":"ok"}` payload as `O` (tests
      * instantiate with `O = StubResult`).
      */
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] =
      new AgentCall[BackendTag.ClaudeCode.type, O]:
        val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
          new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
            def run[I: AgentInput](
                input: I,
                session: SessionId[BackendTag.ClaudeCode.type],
                config: Option[AgentConfig],
                emitPrompt: Boolean
            )(using
                orca.InStage
            ): (SessionId[BackendTag.ClaudeCode.type], O) =
              _capturedPrompt = Some(summon[AgentInput[I]].serialize(input))
              val parsed =
                readFromString[O]("""{"v":"ok"}""")(using
                  summon[JsonData[O]].codec
                )
              (session, parsed)
        def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
          ???
    def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
    def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
    def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
    def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this

  /** A minimal `AgentBackend` stub whose `sessionExists` returns a fixed value.
    * All other methods throw — they must never be called in these tests.
    */
  private class StubBackend(existsResult: Boolean)
      extends AgentBackend[BackendTag.ClaudeCode.type]:
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.ClaudeCode.type],
        config: AgentConfig,
        workDir: os.Path,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.ClaudeCode.type] = ???
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.ClaudeCode.type],
        displayPrompt: String,
        config: AgentConfig,
        workDir: os.Path,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.ClaudeCode.type] = ???
    val sessions: SessionSupport[BackendTag.ClaudeCode.type] =
      stubSupport(existsResult)
    val tag: BackendTag.ClaudeCode.type = BackendTag.ClaudeCode

  /** A minimal `BaseAgent`-derived tool backed by [[StubBackend]]. Exercises
    * the real `BaseAgent.sessionSupport = Some(backend.sessions)` wiring, which
    * `Agent.sessionExists` routes through (`sessions.exists`), in production
    * wiring.
    */
  private class StubBasedTool(backend: StubBackend)
      extends BaseAgent[BackendTag.ClaudeCode.type, Agent[
        BackendTag.ClaudeCode.type
      ]](
        backend,
        AgentConfig(),
        NoOpPrompts,
        os.temp.dir(),
        OrcaListener.noop,
        NoOpInteraction
      ):
    val name: String = "stub-based"
    protected def copyTool(
        config: AgentConfig,
        name: String
    ): Agent[BackendTag.ClaudeCode.type] = this
    override def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] =
      this
    override def withSystemPrompt(
        p: String
    ): Agent[BackendTag.ClaudeCode.type] = this
    override def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
    override def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] =
      this
    override def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] =
      ???

  /** No-op [[Prompts]] for use in [[StubBasedTool]]; never called in these
    * tests because we only exercise `sessionExists`, not the run path.
    */
  private object NoOpPrompts extends Prompts:
    def autonomous(
        input: String,
        outputSchema: String,
        config: AgentConfig
    ): String = ???
    def interactive(
        input: String,
        outputSchema: String,
        config: AgentConfig
    ): String = ???
    def retry(failedResponse: String, parseError: String): String = ???

  /** No-op [[Interaction]] for use in [[StubBasedTool]]. */
  private object NoOpInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    ): AgentResult[B] = ???

  // ── test helpers ──────────────────────────────────────────────────────────

  /** Build a `TestFlowControl` over a temp dir with a header already written.
    * Optionally writes session records and/or completed stage entries so tests
    * can exercise the "progress preamble" and "recorded seed" paths.
    */
  private def makeControl(
      sessions: List[SessionRecord],
      completedStages: List[String] = Nil
  ): TestFlowControl =
    val dir = os.temp.dir()
    val store = ProgressStore.default(dir, "p")
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.writeHeader(ProgressHeader("main", "feat/test", "deadbeef"))
    for record <- sessions do store.upsertSession(record)
    for stageName <- completedStages do
      store.appendEntry(
        StageEntry(id = s"$stageName#0", name = stageName, resultJson = "null")
      )
    val git = new orca.tools.OsGitTool(dir)
    new TestFlowControl(new orca.events.EventDispatcher(Nil), git, store, "p")

  /** A [[FlowSession]] over [[testSession]] and the given stub agent. */
  private def flowSession(
      agent: StubAgentForSeeded
  ): FlowSession[BackendTag.ClaudeCode.type] =
    new FlowSession(agent, testSession)

  // ── tests: free-text run protocol (ported from the former runSeeded) ────────

  test("live session: prompt forwarded verbatim, no preamble, no seed"):
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions =
        List(SessionRecord(occurrence = 0, id = testSessionId, seed = seed))
    )
    val agent = new StubAgentForSeeded(existsResult = true)
    val originalPrompt = "implement feature X"
    val _ = flowSession(agent).run(originalPrompt)(using fc)
    assertEquals(
      agent.capturedPrompt,
      Some(originalPrompt),
      "live session must pass prompt verbatim"
    )

  test(
    "fresh session (not exists, no completed stages): seed + prompt, no preamble"
  ):
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions =
        List(SessionRecord(occurrence = 0, id = testSessionId, seed = seed))
    )
    val agent = new StubAgentForSeeded(existsResult = false)
    val originalPrompt = "implement feature X"
    val _ = flowSession(agent).run(originalPrompt)(using fc)
    val prompt = agent.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(prompt.contains(seed), s"prompt must contain seed; got: $prompt")
    assert(
      prompt.contains(originalPrompt),
      s"prompt must contain original prompt; got: $prompt"
    )
    assert(
      !prompt.contains("Progress so far"),
      s"no preamble expected on first run; got: $prompt"
    )

  test(
    "lost session on resume (not exists, completed stages): preamble + seed + prompt"
  ):
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions =
        List(SessionRecord(occurrence = 0, id = testSessionId, seed = seed)),
      completedStages = List("triage", "implement")
    )
    val agent = new StubAgentForSeeded(existsResult = false)
    val originalPrompt = "continue the work"
    val _ = flowSession(agent).run(originalPrompt)(using fc)
    val prompt = agent.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(
      prompt.contains("Progress so far"),
      s"expected preamble on resume; got: $prompt"
    )
    assert(
      prompt.contains("triage"),
      s"preamble must name completed stage 'triage'; got: $prompt"
    )
    assert(
      prompt.contains("implement"),
      s"preamble must name completed stage 'implement'; got: $prompt"
    )
    // Neutral wording: the preamble is injected both on a true resume and on the
    // first task after an earlier stage in the SAME run, so it must not claim
    // the run was "interrupted".
    assert(
      !prompt.toLowerCase.contains("interrupted"),
      s"preamble wording must stay neutral (no 'interrupted'); got: $prompt"
    )
    assert(prompt.contains(seed), s"prompt must contain seed; got: $prompt")
    assert(
      prompt.contains(originalPrompt),
      s"prompt must contain original prompt; got: $prompt"
    )
    // Contract: preamble precedes seed precedes prompt
    val preambleIdx = prompt.indexOf("Progress so far")
    val seedIdx = prompt.indexOf(seed)
    val promptIdx = prompt.indexOf(originalPrompt)
    assert(
      preambleIdx < seedIdx,
      s"preamble must appear before seed; indices preamble=$preambleIdx seed=$seedIdx"
    )
    assert(
      seedIdx < promptIdx,
      s"seed must appear before prompt; indices seed=$seedIdx prompt=$promptIdx"
    )

  test(
    "no recorded seed for session: captured prompt equals bare original prompt"
  ):
    // Session id not in the log -> seed treated as absent; no preamble
    // (no completed stages) -> prompt must be forwarded verbatim with no
    // leading `---` separator or seed blob.
    val fc = makeControl(sessions = Nil)
    val agent = new StubAgentForSeeded(existsResult = false)
    val originalPrompt = "do something"
    val _ = flowSession(agent).run(originalPrompt)(using fc)
    assertEquals(
      agent.capturedPrompt,
      Some(originalPrompt),
      "no seed + no preamble must produce bare original prompt, not '---\\n\\n' + prompt"
    )

  test(
    "no seed but completed stages: captured prompt has preamble and prompt, no seed blob, no stray separator"
  ):
    // Session exists in log with empty seed; there are completed stages so
    // a preamble is generated.  The prompt must contain the preamble and the
    // original prompt but MUST NOT contain a seed blob (it's empty) and MUST
    // NOT start with `---` (the separator only appears between context and prompt).
    val fc = makeControl(
      sessions =
        List(SessionRecord(occurrence = 0, id = testSessionId, seed = "")),
      completedStages = List("triage")
    )
    val agent = new StubAgentForSeeded(existsResult = false)
    val originalPrompt = "continue"
    val _ = flowSession(agent).run(originalPrompt)(using fc)
    val prompt = agent.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(
      prompt.contains("Progress so far"),
      s"expected preamble when stages completed; got: $prompt"
    )
    assert(
      prompt.contains(originalPrompt),
      s"prompt must contain original prompt; got: $prompt"
    )
    assert(
      !prompt.startsWith("---"),
      s"prompt must not start with bare separator; got: $prompt"
    )

  test("run returns the output from autonomous.run; .id is the session id"):
    val seed = "seed text"
    val fc = makeControl(
      sessions =
        List(SessionRecord(occurrence = 0, id = testSessionId, seed = seed))
    )
    val agent =
      new StubAgentForSeeded(existsResult = false, runResult = "agent output")
    val session = flowSession(agent)
    val output = session.run("prompt")(using fc)
    assertEquals(session.id, testSession)
    assertEquals(output, "agent output")

  test(
    "run persists a newly-learned wire id into the SessionRecord"
  ):
    val fc = makeControl(
      sessions =
        List(SessionRecord(occurrence = 0, id = testSessionId, seed = "seed"))
    )
    val agent = new StubAgentForSeeded(
      existsResult = false,
      learnedWireId = Some("server-thread-xyz")
    )
    val _ = flowSession(agent).run("prompt")(using fc)
    val record =
      fc.progressStore.load().get.sessions.find(_.id == testSessionId).get
    assertEquals(record.resumeWireId, Some("server-thread-xyz"))

  test(
    "run leaves resumeWireId None when the backend reports no wire id"
  ):
    // pi: ephemeral sessions, resumeWireId returns None.
    val fc = makeControl(
      sessions =
        List(SessionRecord(occurrence = 0, id = testSessionId, seed = "seed"))
    )
    val agent =
      new StubAgentForSeeded(existsResult = false, learnedWireId = None)
    val _ = flowSession(agent).run("prompt")(using fc)
    val record =
      fc.progressStore.load().get.sessions.find(_.id == testSessionId).get
    assertEquals(record.resumeWireId, None)

  test(
    "run does NOT clobber a previously-persisted resumeWireId when the backend reports None"
  ):
    // The guard in persistResumeWireId calls agent.resumeWireId(session).foreach { … }
    // so a None result short-circuits and the record's resumeWireId is left intact.
    // Pre-seed the log with a SessionRecord whose resumeWireId is already
    // Some("server-1"), then run with a stub whose resumeWireId returns None, and
    // confirm the stored resumeWireId is still Some("server-1") (not cleared).
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          occurrence = 0,
          id = testSessionId,
          seed = "seed",
          resumeWireId = Some("server-1")
        )
      )
    )
    val agent =
      new StubAgentForSeeded(existsResult = false, learnedWireId = None)
    val _ = flowSession(agent).run("prompt")(using fc)
    val record =
      fc.progressStore.load().get.sessions.find(_.id == testSessionId).get
    assertEquals(
      record.resumeWireId,
      Some("server-1"),
      "a previously-persisted resumeWireId must NOT be clobbered when the backend reports None"
    )

  // ── tests: structured resultAs durable door ─────────────────────────────────

  test(
    "resultAs.run primes seed + preamble and persists wire id on a lost session"
  ):
    // The structured door had NO seed/probe/persist path before FlowSession
    // (it went through the raw door); assert it now follows the same protocol.
    val seed = "You are a fixer."
    val fc = makeControl(
      sessions =
        List(SessionRecord(occurrence = 0, id = testSessionId, seed = seed)),
      completedStages = List("triage")
    )
    val agent = new StubAgentForSeeded(
      existsResult = false,
      learnedWireId = Some("server-structured-1")
    )
    val result =
      flowSession(agent)
        .resultAs[StubResult]
        .run("do the fix")(using
          fc
        )
    assertEquals(result, StubResult("ok"))
    val prompt = agent.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(
      prompt.contains("Progress so far"),
      s"structured door must inject the preamble; got: $prompt"
    )
    assert(
      prompt.contains(seed),
      s"structured door must inject seed; got: $prompt"
    )
    assert(
      prompt.contains("do the fix"),
      s"structured door must include the input; got: $prompt"
    )
    val record =
      fc.progressStore.load().get.sessions.find(_.id == testSessionId).get
    assertEquals(record.resumeWireId, Some("server-structured-1"))

  test("resultAs.run on a live session forwards the input verbatim"):
    val fc = makeControl(
      sessions =
        List(SessionRecord(occurrence = 0, id = testSessionId, seed = "seed"))
    )
    val agent = new StubAgentForSeeded(existsResult = true)
    val _ =
      flowSession(agent)
        .resultAs[StubResult]
        .run("continue")(using
          fc
        )
    assertEquals(agent.capturedPrompt, Some("continue"))

  // ── tests: session() returns a FlowSession handle ───────────────────────────

  test("agent.session returns a FlowSession whose .id is the recorded id"):
    val fc = makeControl(sessions = Nil)
    val agent = new StubAgentForSeeded(existsResult = false)
    val session: FlowSession[BackendTag.ClaudeCode.type] =
      agent.session("implementer", "brief")(using fc)
    val recorded = fc.progressStore.load().get.sessions.head
    assertEquals(session.id.value, recorded.id)

  // ── tests: the raw ephemeral door does not accept a FlowSession ──────────────

  test("agent.autonomous.run(prompt, flowSession) does not compile"):
    val errors = compileErrors(
      """
      val agent = new StubAgentForSeeded(existsResult = true)
      val session = new FlowSession(agent, testSession)
      given orca.InStage = orca.InStage.unsafe
      agent.autonomous.run("prompt", session)
      """
    )
    // Pin the actual mismatch, not just "some error" — the raw door expects a
    // `SessionId`, and a `FlowSession` must not satisfy that by accident.
    assert(
      errors.contains("Found") && errors.contains("orca.FlowSession") &&
        errors.contains("Required") && errors.contains("orca.agents.SessionId"),
      s"expected a Found FlowSession / Required SessionId type mismatch, got: $errors"
    )

  // ── tests: sessionExists delegation (unchanged) ─────────────────────────────

  test(
    "Agent.sessionExists: BaseAgent delegates to backend (returns false)"
  ):
    // Real delegation: StubBasedTool → BaseAgent.sessionExists → StubBackend.sessionExists
    val tool = new StubBasedTool(new StubBackend(existsResult = false))
    assert(!tool.sessionExists(testSession))

  test(
    "Agent.sessionExists: BaseAgent delegates to backend (returns true)"
  ):
    // Real delegation: StubBasedTool → BaseAgent.sessionExists → StubBackend.sessionExists
    val tool = new StubBasedTool(new StubBackend(existsResult = true))
    assert(tool.sessionExists(testSession))
