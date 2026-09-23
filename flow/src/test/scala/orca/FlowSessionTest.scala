package orca

import munit.FunSuite
import orca.backend.{Dispatch, IdScheme, SessionSupport}
import orca.agents.{
  SessionKey,
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
  SessionId,
  WireSessionId,
  ToolSet,
  onWire
}
import orca.progress.{BranchMode, ProgressHeader, ProgressStore, StageEntry}
import orca.sessions.{SessionRecord, SessionStore}
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.testkit.{GitRepo, TempDirs}
import orca.util.RawJson

/** Tests for [[FlowSession]] — the durable-session handle that owns the probe →
  * seed/preamble → run → persist protocol (ADR 0018 §2.6).
  *
  * Each scenario constructs a [[FlowSession]] directly over a
  * [[StubAgentForSeeded]] (whose dispatch and `run` behaviours are injected at
  * construction time) and a fixed [[testSession]] id, and asserts on
  * `capturedPrompt` (what the prompt looked like after preamble/seed
  * composition) and on the persisted [[SessionRecord]].
  */
class FlowSessionTest extends FunSuite:

  // `FlowSession.run`/`resultAs` are gated on `InStage` + `WorkspaceWrite`
  // (the explicit mutation token, per ADR 0018 §6); mint both for the suite.
  private given orca.InStage = orca.InStage.unsafe
  private given orca.WorkspaceWrite = orca.WorkspaceWrite.unsafe

  /** Builds the durability capability the stubs expose through
    * `sessionSupport`. When `exists`, a mapping this run committed is recorded
    * through the public `register` door, so the conversation reads as live and
    * opened by this run.
    */
  private def stubSupport(
      exists: Boolean,
      session: SessionId[BackendTag.ClaudeCode.type]
  ): SessionSupport[BackendTag.ClaudeCode.type] =
    val support = SessionSupport
      .durable[BackendTag.ClaudeCode.type](IdScheme.ServerMinted, _ => exists)
    if exists then
      support.register(
        session,
        WireSessionId[BackendTag.ClaudeCode.type]("live-session-wire")
      )
    support

  /** Builds a durability capability holding the wire id a previous run
    * recorded, rehydrated as the runtime does before any turn; the probe
    * answers `exists`.
    */
  private def rehydratedSupport(
      exists: Boolean,
      session: SessionId[BackendTag.ClaudeCode.type]
  ): SessionSupport[BackendTag.ClaudeCode.type] =
    val support = SessionSupport
      .durable[BackendTag.ClaudeCode.type](IdScheme.ServerMinted, _ => exists)
    support.rehydrate(
      session,
      WireSessionId[BackendTag.ClaudeCode.type]("wire-1")
    )
    support

  /** A fixed session id used across all tests; avoids UUID randomness in
    * assertions and lets `makeControl` pre-populate the log without forward
    * references.
    */
  private val testSessionId = "test-session-uuid-1234"
  private val testSession: SessionId[BackendTag.ClaudeCode.type] =
    SessionId[BackendTag.ClaudeCode.type](testSessionId)

  /** The key every test's [[FlowSession]] is minted under. */
  private val testSessionKey =
    SessionKey(name = "coder", stage = StagePath.FlowBody.child("Task 2", 0))

  /** A structured result type for exercising the `resultAs[O]` durable door. */
  private case class StubResult(v: String) derives JsonData

  /** Which session-durability shape a stub exposes — the ones the durable door
    * has to tell apart.
    */
  private enum StubDurability:
    /** Durable and server-minted: when the stub's `existsResult` is set, this
      * run committed a mapping.
      */
    case Committed

    /** Durable and server-minted, with the wire id a previous run recorded
      * rehydrated as the runtime does; the probe answers the stub's
      * `existsResult`.
      */
    case Rehydrated

    /** Ephemeral: no transcript to probe, so the in-run claim is all there is.
      */
    case InProcess

    /** Durable and client-claimed, with nothing recorded: the backend holds a
      * conversation under the client's own id. What a run interrupted during a
      * session's first turn leaves behind.
      */
    case HeldClaim

  /** Controllable Agent stub for seeded-run tests.
    *
    * @param existsResult
    *   Whether the conversation is live — set `true` to exercise the "live
    *   session" branch, `false` to exercise the re-seed path. Read only under
    *   [[StubDurability.Committed]] and [[StubDurability.Rehydrated]].
    * @param runResult
    *   The text `autonomous.run` echoes back.
    * @param learnedWireId
    *   The wire id the stub's backend learns during a turn, committed after it
    *   the way a server-minting backend commits.
    * @param durableSession
    *   The session id the durable fixture records a wire mapping for.
    */
  private class StubAgentForSeeded(
      existsResult: Boolean,
      runResult: String = "ok",
      learnedWireId: Option[String] = None,
      durability: StubDurability = StubDurability.Committed,
      tag: Option[BackendTag] = None,
      durableSession: SessionId[BackendTag.ClaudeCode.type] = testSession
  ) extends Agent[BackendTag.ClaudeCode.type]:
    val name: String = "stub-seeded"

    /** [[Agent.backendTag]] override — `None` by default, settable so a
      * self-heal test can drive `persistResumeWireId`'s tag-healing write with
      * a concrete tag.
      */
    override private[orca] def backendTag: Option[BackendTag] = tag

    private var _capturedPrompts: List[String] = Nil
    private var _capturedSessionKeys: List[Option[SessionKey]] = Nil
    private var _capturedDispatches
        : List[Dispatch[BackendTag.ClaudeCode.type]] =
      Nil

    /** What a backend would have put on the wire for the most recent `run`. */
    def capturedDispatch: Option[Dispatch[BackendTag.ClaudeCode.type]] =
      _capturedDispatches.headOption

    /** The prompt the stub's most recent `run` (free-text or structured)
      * received, after preamble/seed composition.
      */
    def capturedPrompt: Option[String] = _capturedPrompts.headOption

    /** Every captured prompt in call order (oldest first) — lets a multi-run
      * test compare the first (primed) turn against a later (continued) one.
      */
    def capturedPrompts: List[String] = _capturedPrompts.reverse

    /** Every `sessionKey` the stub's `run`s received, in call order (oldest
      * first) — what the durable door hands to the emission edge.
      */
    def capturedSessionKeys: List[Option[SessionKey]] =
      _capturedSessionKeys.reverse

    /** The durability capability the stub exposes (a STABLE instance, so a
      * claim recorded by one run persists into the next). `learnedWireId`
      * mirrors a server-id backend's persist path.
      */
    private val support: SessionSupport[BackendTag.ClaudeCode.type] =
      durability match
        case StubDurability.InProcess =>
          SessionSupport.ephemeral[BackendTag.ClaudeCode.type](
            IdScheme.ClientClaimed
          )
        case StubDurability.HeldClaim =>
          SessionSupport.durable[BackendTag.ClaudeCode.type](
            IdScheme.ClientClaimed,
            _ => true
          )
        case StubDurability.Committed =>
          stubSupport(existsResult, durableSession)
        case StubDurability.Rehydrated =>
          rehydratedSupport(existsResult, durableSession)

    /** Drives `dispatchFor` and `resumeWireId`. */
    override private[orca] def sessionSupport
        : Option[SessionSupport[BackendTag.ClaudeCode.type]] =
      Some(support)

    /** Record the prompt and the dispatch a backend would spawn with, then
      * commit as a real backend does after a clean turn (via
      * `Conversations.drainAndCommit`): the ephemeral shape claims the id, a
      * `learnedWireId` is recorded.
      */
    private def capture(
        prompt: String,
        session: SessionId[BackendTag.ClaudeCode.type],
        sessionKey: Option[SessionKey]
    ): Unit =
      _capturedPrompts = prompt :: _capturedPrompts
      _capturedSessionKeys = sessionKey :: _capturedSessionKeys
      _capturedDispatches = support.dispatchFor(session) :: _capturedDispatches
      if durability == StubDurability.InProcess then
        support.register(session, session.onWire)
      learnedWireId.foreach(w =>
        support.register(session, WireSessionId[BackendTag.ClaudeCode.type](w))
      )

    val autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
      new AutonomousTextCall[BackendTag.ClaudeCode.type]:
        private[orca] def runWithSession(
            prompt: String,
            session: SessionId[BackendTag.ClaudeCode.type],
            sessionKey: Option[SessionKey],
            config: Option[AgentConfig],
            emitPrompt: Boolean
        )(using orca.InStage): String =
          capture(prompt, session, sessionKey)
          runResult

    /** Structured door stub: captures the serialized input (after preamble/seed
      * composition) and decodes a fixed `{"v":"ok"}` payload as `O` (tests
      * instantiate with `O = StubResult`).
      */
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] =
      new AgentCall[BackendTag.ClaudeCode.type, O]:
        val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
          new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
            private[orca] def runWithSession[I: AgentInput](
                input: I,
                session: SessionId[BackendTag.ClaudeCode.type],
                sessionKey: Option[SessionKey],
                config: Option[AgentConfig],
                emitPrompt: Boolean
            )(using
                orca.InStage
            ): O =
              capture(
                summon[AgentInput[I]].serialize(input),
                session,
                sessionKey
              )
              val parsed =
                readFromString[O]("""{"v":"ok"}""")(using
                  summon[JsonData[O]].codec
                )
              parsed
        def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
          ???
    def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
    def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
    def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
    def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this

  // ── test helpers ──────────────────────────────────────────────────────────

  /** Build a `TestFlowControl` over a temp dir with a header already written.
    * Optionally writes session records and/or completed stage entries so tests
    * can exercise the "progress preamble" and "recorded seed" paths.
    */
  private def makeControl(
      sessions: List[SessionRecord],
      completedStages: List[String] = Nil,
      listeners: List[orca.events.OrcaListener] = Nil
  ): TestFlowControl =
    val dir = TempDirs.dir()
    val store = ProgressStore.default(dir, RunKey.of("p"))
    val sessionStore = SessionStore.default(dir, RunKey.of("p"))
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.writeHeader(
      ProgressHeader(
        "main",
        "feat/test",
        BranchMode.Created,
        userPrompt = "p",
        flowName = None,
        startingCommit = orca.progress.CommitHash.from("0" * 40).get
      )
    )
    for record <- sessions do sessionStore.upsert(record)
    for stageName <- completedStages do
      store.upsertEntry(
        StageEntry(
          id = s"$stageName#0",
          name = stageName,
          resultJson = RawJson("null")
        )
      )
    val git = new orca.tools.OsGitTool(dir)
    new TestFlowControl(
      new orca.events.EventDispatcher(listeners),
      git,
      store,
      sessionStore,
      "p"
    )

  /** A record whose `resumeWireId` is set is one a PREVIOUS run committed a
    * turn against — what tells the runtime a live conversation predates this
    * run.
    */
  private def carriedOverRecord(name: String, id: String): SessionRecord =
    SessionRecord(
      name = name,
      stage = "",
      id = id,
      seed = "seed",
      resumeWireId = Some("wire-1"),
      backend = None
    )

  private def carriedOver: List[SessionRecord] =
    List(carriedOverRecord("s", testSessionId))

  /** The notice's operative instruction — the half that tells the agent what to
    * do about the tree, and what an inverted body would lose.
    */
  private val NoticeInstruction =
    "Read the files rather than relying on what you remember writing"

  /** A second durable conversation, for the per-conversation claim. */
  private val otherSessionId = "test-session-uuid-5678"
  private val otherSession: SessionId[BackendTag.ClaudeCode.type] =
    SessionId[BackendTag.ClaudeCode.type](otherSessionId)
  private val otherSessionKey =
    SessionKey(name = "reviewer", stage = StagePath.FlowBody.child("Task 3", 0))

  /** A [[FlowSession]] over [[testSession]] and the given stub agent, minted
    * under [[testSessionKey]].
    */
  private def flowSession(
      agent: StubAgentForSeeded
  ): FlowSession[BackendTag.ClaudeCode.type] =
    new FlowSession(agent, testSession, testSessionKey)

  // ── tests: free-text run protocol ───────────────────────────────────────────

  test("live session: prompt forwarded verbatim, no preamble, no seed"):
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = seed,
          resumeWireId = None,
          backend = None
        )
      )
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
    "conversation carried over from a previous run: its first turn is told the tree lost the uncommitted work"
  ):
    val fc = makeControl(sessions = carriedOver)
    val agent = new StubAgentForSeeded(
      existsResult = true,
      durability = StubDurability.Rehydrated
    )
    val _ = flowSession(agent).run("continue the task")(using fc)
    val prompt = agent.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(
      prompt.contains("The previous attempt at this run was interrupted."),
      s"expected the interrupted-attempt notice; got: $prompt"
    )
    assert(
      prompt.contains(
        "working tree holds only what earlier stages committed"
      ),
      s"the notice must say what the tree holds; got: $prompt"
    )
    assert(
      prompt.contains(NoticeInstruction),
      s"the notice must send the agent to the files; got: $prompt"
    )
    assert(
      prompt.endsWith("continue the task"),
      s"the caller's prompt must follow the notice; got: $prompt"
    )

  test(
    "conversation the backend still holds with nothing recorded: told, and resumed rather than re-seeded"
  ):
    // A run interrupted during a client-claimed session's FIRST turn commits no
    // wire id, so the record carries none — but the backend wrote the
    // transcript and holds the conversation under the claimed id. It is
    // continued, and its memory predates this run just as a recorded one's
    // does.
    val fc = makeControl(sessions =
      List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = "You are a planning agent.",
          resumeWireId = None,
          backend = None
        )
      )
    )
    val agent = new StubAgentForSeeded(
      existsResult = false,
      durability = StubDurability.HeldClaim
    )
    val _ = flowSession(agent).run("continue the task")(using fc)
    val prompt = agent.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(
      prompt.contains(NoticeInstruction),
      s"an interrupted first turn's conversation must be told; got: $prompt"
    )
    assert(
      !prompt.contains("You are a planning agent."),
      s"a continued conversation must not be re-seeded; got: $prompt"
    )

  test(
    "carried-over conversation: the notice is said once, not on every turn"
  ):
    // The fixer drives one session for several turns inside a stage; from the
    // second turn the uncommitted edits in the tree are this run's own.
    val fc = makeControl(sessions = carriedOver)
    val agent = new StubAgentForSeeded(
      existsResult = true,
      durability = StubDurability.Rehydrated
    )
    val session = flowSession(agent)
    val _ = session.run("first")(using fc)
    val _ = session.run("second")(using fc)
    assertEquals(
      agent.capturedPrompts(1),
      "second",
      "a later turn must forward the prompt verbatim"
    )

  test(
    "carried-over conversation the backend lost: re-seeded, and not also told"
  ):
    // The seed + preamble path already says an unfinished stage left nothing
    // behind, so a second telling would be a duplicate.
    val fc =
      makeControl(sessions = carriedOver, completedStages = List("triage"))
    val agent = new StubAgentForSeeded(
      existsResult = false,
      durability = StubDurability.Rehydrated
    )
    val _ = flowSession(agent).run("continue")(using fc)
    val prompt = agent.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(
      prompt.contains("a stage that did not complete left nothing behind"),
      s"the preamble must be the telling here; got: $prompt"
    )
    assert(
      !prompt.contains("The previous attempt at this run was interrupted."),
      s"a re-seeded session must not carry the notice too; got: $prompt"
    )

  test(
    "carried-over conversation the backend lost: the wire opens fresh too"
  ):
    // The prompt side re-seeds, so the spawn must not resume the lost id.
    val fc = makeControl(sessions = carriedOver)
    val agent = new StubAgentForSeeded(
      existsResult = false,
      durability = StubDurability.Rehydrated
    )
    val _ = flowSession(agent).run("continue")(using fc)
    assertEquals(agent.capturedDispatch, Some(Dispatch.Fresh(None)))

  test(
    "a second carried-over conversation is told on its own first turn"
  ):
    // One flag per run would leave every conversation after the first untold,
    // though each one's memory predates the run just as the first's does. The
    // two records need distinct names: the store keys a record by (name,
    // stage).
    val fc = makeControl(sessions =
      List(
        carriedOverRecord("s", testSessionId),
        carriedOverRecord("other", otherSessionId)
      )
    )
    val first = new StubAgentForSeeded(
      existsResult = true,
      durability = StubDurability.Rehydrated
    )
    val second = new StubAgentForSeeded(
      existsResult = true,
      durability = StubDurability.Rehydrated,
      durableSession = otherSession
    )
    val _ = flowSession(first).run("first conversation")(using fc)
    val _ = new FlowSession(second, otherSession, otherSessionKey)
      .run("second conversation")(using fc)
    assert(
      first.capturedPrompt.exists(_.contains(NoticeInstruction)),
      s"the first conversation must be told; got: ${first.capturedPrompt}"
    )
    assert(
      second.capturedPrompt.exists(_.contains(NoticeInstruction)),
      s"the second conversation must be told too; got: ${second.capturedPrompt}"
    )

  test(
    "conversation opened by THIS run: a later turn is not told, though the record carries a wire id"
  ):
    // The stub claims the id after its first turn, so turn 2 finds the
    // conversation live — the shape of a session whose recorded conversation
    // was gone and which this run reopened. The turn claim is taken on every
    // turn, so turn 2 is not mistaken for the first.
    val fc = makeControl(sessions = carriedOver)
    val agent = new StubAgentForSeeded(
      existsResult = false,
      durability = StubDurability.InProcess
    )
    val session = flowSession(agent)
    val _ = session.run("first")(using fc)
    val _ = session.run("second")(using fc)
    assertEquals(
      agent.capturedPrompts(1),
      "second",
      "a conversation this run opened must not be told its work was lost"
    )

  test(
    "fresh session (not exists, no completed stages): seed + prompt, no preamble"
  ):
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = seed,
          resumeWireId = None,
          backend = None
        )
      )
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

  test("session.run from a fork is rejected at runtime (R12)"):
    // The durable door persists to the progress log, which is single-threaded
    // per flow — the owner-thread assert refuses it off the flow thread even
    // in code the capture checker never sees (a plain .sc script's fork).
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = "x",
          resumeWireId = None,
          backend = None
        )
      )
    )
    val agent = new StubAgentForSeeded(existsResult = true)
    import ox.*
    val outcome = supervised:
      fork(scala.util.Try(flowSession(agent).run("p")(using fc))).join()
    val thrown = outcome.failed.toOption
    assert(
      thrown.exists(e =>
        e.isInstanceOf[OrcaFlowException] &&
          e.getMessage.contains("session.run(...)") &&
          e.getMessage.contains("R12")
      ),
      s"expected THIS door's R12 rejection; got: $outcome"
    )

  test("re-seeding a previously-live session emits a Step warning"):
    // A recorded resumeWireId proves a backend conversation once existed;
    // rebuilding it replays only seed + preamble, not the prior turns — that
    // context loss must be visible in the flow's output.
    val steps = scala.collection.mutable.ListBuffer.empty[String]
    val listener = new orca.events.OrcaListener:
      def onEvent(event: orca.events.OrcaEvent): Unit = event match
        case orca.events.OrcaEvent.Step(msg) => steps += msg
        case _                               => ()
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = "seed",
          resumeWireId = Some("wire-1"),
          backend = None
        )
      ),
      listeners = List(listener)
    )
    val agent = new StubAgentForSeeded(existsResult = false)
    val _ = flowSession(agent).run("continue")(using fc)
    assert(
      steps.exists(s => s.contains("re-seeding") && s.contains("'s'")),
      s"expected a re-seed warning naming the session; got: $steps"
    )

  test("a plain first use (no recorded wire id) re-seeds without a warning"):
    val steps = scala.collection.mutable.ListBuffer.empty[String]
    val listener = new orca.events.OrcaListener:
      def onEvent(event: orca.events.OrcaEvent): Unit = event match
        case orca.events.OrcaEvent.Step(msg) => steps += msg
        case _                               => ()
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = "x",
          resumeWireId = None,
          backend = None
        )
      ),
      listeners = List(listener)
    )
    val agent = new StubAgentForSeeded(existsResult = false)
    val _ = flowSession(agent).run("kick off")(using fc)
    assert(
      !steps.exists(_.contains("re-seeding")),
      s"first use must not warn; got: $steps"
    )

  test(
    "lost session on resume (not exists, completed stages): preamble + seed + prompt"
  ):
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = seed,
          resumeWireId = None,
          backend = None
        )
      ),
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
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = "",
          resumeWireId = None,
          backend = None
        )
      ),
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

  test(
    "re-seeded session: the preamble names the commit the tree is at and says an unfinished stage left nothing"
  ):
    // A real repo, unlike `makeControl`'s bare temp dir, so `headCommit()` has
    // something to report.
    val dir = GitRepo.seeded()
    val store = ProgressStore.default(dir, RunKey.of("p"))
    val sessionStore = SessionStore.default(dir, RunKey.of("p"))
    store.writeHeader(
      ProgressHeader(
        "main",
        "feat/test",
        BranchMode.Created,
        userPrompt = "p",
        flowName = None,
        startingCommit = orca.progress.CommitHash.from("0" * 40).get
      )
    )
    sessionStore.upsert(
      SessionRecord(
        name = "s",
        stage = "",
        id = testSessionId,
        seed = "",
        resumeWireId = None,
        backend = None
      )
    )
    store.upsertEntry(
      StageEntry(id = "triage#0", name = "triage", resultJson = RawJson("null"))
    )
    val git = new orca.tools.OsGitTool(dir)
    val fc = new TestFlowControl(
      new orca.events.EventDispatcher(Nil),
      git,
      store,
      sessionStore,
      "p"
    )
    val agent = new StubAgentForSeeded(existsResult = false)
    val _ = flowSession(agent).run("continue")(using fc)
    val prompt = agent.capturedPrompt.getOrElse(fail("no prompt captured"))
    val head = git.headCommit().getOrElse(fail("no HEAD in the seeded repo"))
    assert(
      prompt.contains(s"The working tree is at commit $head."),
      s"the preamble must name the commit; got: $prompt"
    )
    assert(
      prompt.contains("a stage that did not complete left nothing behind"),
      s"the preamble must say an unfinished stage's edits are gone; got: $prompt"
    )

  test(
    "Ephemeral session: a second in-process run does NOT re-prime"
  ):
    // An ephemeral backend has no durable transcript to probe, so an
    // exists-based probe would re-seed every task of a loop; the continuation
    // reads the in-process claim, so a live one runs the prompt verbatim. The stub claims the id after each run (as a real
    // drainAndCommit does), so the SECOND run must NOT re-inject seed/preamble.
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = seed,
          resumeWireId = None,
          backend = None
        )
      )
    )
    val agent = new StubAgentForSeeded(
      existsResult = false,
      durability = StubDurability.InProcess
    )
    val fs = flowSession(agent)
    val _ = fs.run("task one")(using fc)
    val _ = fs.run("task two")(using fc)
    val prompts = agent.capturedPrompts
    assert(
      prompts(0).contains(seed),
      s"first run must prime with the seed; got: ${prompts(0)}"
    )
    assertEquals(
      prompts(1),
      "task two",
      "a second in-process run must forward the prompt verbatim (no re-seed)"
    )

  test("run hands the session's key to the turn, for SessionCommitted"):
    // The manifest's session name, minting stage and `kind` all come off the
    // event, so the whole key has to reach the emission edge from here.
    val fc = makeControl(sessions = Nil)
    val agent = new StubAgentForSeeded(existsResult = true)
    val _ = flowSession(agent).run("prompt")(using fc)
    assertEquals(agent.capturedSessionKeys, List(Some(testSessionKey)))

  test("resultAs.run hands the session's key to the turn"):
    val fc = makeControl(sessions = Nil)
    val agent = new StubAgentForSeeded(existsResult = true)
    val _ = flowSession(agent).resultAs[StubResult].run("prompt")(using fc)
    assertEquals(agent.capturedSessionKeys, List(Some(testSessionKey)))

  test("run returns the output from autonomous.run; .id is the session id"):
    val seed = "seed text"
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = seed,
          resumeWireId = None,
          backend = None
        )
      )
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
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = "seed",
          resumeWireId = None,
          backend = None
        )
      )
    )
    val agent = new StubAgentForSeeded(
      existsResult = false,
      learnedWireId = Some("server-thread-xyz")
    )
    val _ = flowSession(agent).run("prompt")(using fc)
    val record =
      fc.sessionStore.records().find(_.id == testSessionId).get
    assertEquals(record.resumeWireId, Some("server-thread-xyz"))

  test(
    "run self-heals an untagged recorded backend when it persists a wire id (6B.2)"
  ):
    // The record predates tagging (backend = None — an untagged, pre-tagging
    // log). This is the ONLY backend value `persistResumeWireId`'s self-heal
    // ever sees on a genuinely-reused session: `session(...)`'s reuse arm
    // already refuses to reuse a record whose tag actively MISMATCHES the
    // current agent (it mints fresh instead, never reaching this run), so a
    // tagged-mismatch case can't reach `persistResumeWireId` at all. This run's
    // agent is ClaudeCode and learns a wire id — persistResumeWireId must
    // upgrade `backend` from `None` to `ClaudeCode`, not leave it unset via
    // `.copy`.
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = "seed",
          backend = None,
          resumeWireId = None
        )
      )
    )
    val agent = new StubAgentForSeeded(
      existsResult = false,
      learnedWireId = Some("server-thread-xyz"),
      tag = Some(BackendTag.ClaudeCode)
    )
    val _ = flowSession(agent).run("prompt")(using fc)
    val record =
      fc.sessionStore.records().find(_.id == testSessionId).get
    assertEquals(record.resumeWireId, Some("server-thread-xyz"))
    assertEquals(
      record.backend,
      Some(BackendTag.ClaudeCode),
      "an untagged recorded backend must be healed to the agent's current tag"
    )

  test(
    "run leaves resumeWireId None when the backend reports no wire id"
  ):
    // Ephemeral sessions: resumeWireId returns None.
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = "seed",
          resumeWireId = None,
          backend = None
        )
      )
    )
    val agent =
      new StubAgentForSeeded(existsResult = false, learnedWireId = None)
    val _ = flowSession(agent).run("prompt")(using fc)
    val record =
      fc.sessionStore.records().find(_.id == testSessionId).get
    assertEquals(record.resumeWireId, None)

  test(
    "run does NOT clobber a previously-persisted resumeWireId when the backend reports None"
  ):
    // The guard in persistResumeWireId calls agent.resumeWireId(session).foreach { … }
    // so a None result short-circuits and the record's resumeWireId is left intact.
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = "seed",
          resumeWireId = Some("server-1"),
          backend = None
        )
      )
    )
    val agent =
      new StubAgentForSeeded(existsResult = false, learnedWireId = None)
    val _ = flowSession(agent).run("prompt")(using fc)
    val record =
      fc.sessionStore.records().find(_.id == testSessionId).get
    assertEquals(
      record.resumeWireId,
      Some("server-1"),
      "a previously-persisted resumeWireId must NOT be clobbered when the backend reports None"
    )

  // ── tests: structured resultAs durable door ─────────────────────────────────

  test(
    "resultAs.run primes seed + preamble and persists wire id on a lost session"
  ):
    // The structured door must follow the same seed/probe/persist protocol as
    // the free-text door.
    val seed = "You are a fixer."
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = seed,
          resumeWireId = None,
          backend = None
        )
      ),
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
      fc.sessionStore.records().find(_.id == testSessionId).get
    assertEquals(record.resumeWireId, Some("server-structured-1"))

  test(
    "resultAs.run tells a carried-over conversation on its first turn"
  ):
    // The fix turn drives this door exclusively, so a resume whose first
    // durable turn is a fix turn depends on it.
    val fc = makeControl(sessions = carriedOver)
    val agent = new StubAgentForSeeded(
      existsResult = true,
      durability = StubDurability.Rehydrated
    )
    val _ = flowSession(agent)
      .resultAs[StubResult]
      .run("continue the task")(using fc)
    val prompt = agent.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(
      prompt.contains(NoticeInstruction),
      s"the structured door must carry the notice; got: $prompt"
    )

  test("resultAs.run on a live session forwards the input verbatim"):
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          stage = "",
          id = testSessionId,
          seed = "seed",
          resumeWireId = None,
          backend = None
        )
      )
    )
    val agent = new StubAgentForSeeded(existsResult = true)
    val _ =
      flowSession(agent)
        .resultAs[StubResult]
        .run("continue")(using
          fc
        )
    assertEquals(agent.capturedPrompt, Some("continue"))

  // ── tests: the chat(...) hatch rejects a FlowSession (takes .id) ────────────

  test("agent.chat(flowSession) does not compile — the hatch takes .id"):
    val errors = compileErrors(
      """
      val agent = new StubAgentForSeeded(existsResult = true)
      val session = new FlowSession(agent, testSession, testSessionKey)
      val _ = agent.chat(session)
      """
    )
    // Pin the actual mismatch, not just "some error" — the hatch expects a
    // `SessionId`, and a `FlowSession` must not satisfy that by accident.
    assert(
      errors.contains("Found") && errors.contains("orca.FlowSession") &&
        errors.contains("Required") && errors.contains("SessionId"),
      s"expected a Found FlowSession / Required SessionId type mismatch, got: $errors"
    )
