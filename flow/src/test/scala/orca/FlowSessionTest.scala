package orca

import munit.FunSuite
import orca.backend.{IdScheme, SessionSupport}
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
  SessionId,
  WireSessionId,
  ToolSet,
  onWire
}
import orca.progress.{ProgressHeader, ProgressStore, StageEntry, SessionRecord}
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.testkit.TempDirs
import orca.util.RawJson

/** Tests for [[FlowSession]] — the durable-session handle that owns the probe →
  * seed/preamble → run → persist protocol (ADR 0018 §2.6, absorbs the former
  * `agent.runSeeded` extension).
  *
  * Each scenario constructs a [[FlowSession]] directly over a
  * [[StubAgentForSeeded]] (whose `willContinue` and `run` behaviours are
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
    * `sessionSupport`, seeding it via the public `register` door. Both
    * `willContinue` and `resumeWireId` route through it (the trio is `final` on
    * [[Agent]], and the probe only runs for a recorded mapping). A safe
    * placeholder wire id is registered when `exists` is wanted so the probe
    * (which returns `exists`) runs; otherwise the caller-supplied
    * `learnedWireId`, if any, is registered, which is what `resumeWireId`
    * surfaces for persistence.
    */
  private def stubSupport(
      exists: Boolean,
      learnedWireId: Option[String]
  ): SessionSupport[BackendTag.ClaudeCode.type] =
    val support = SessionSupport
      .durable[BackendTag.ClaudeCode.type](IdScheme.ServerMinted, _ => exists)
    (if exists then Some("live-session-wire") else learnedWireId)
      .foreach(w =>
        support.register(
          testSession,
          WireSessionId[BackendTag.ClaudeCode.type](w)
        )
      )
    support

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
    *   The value the durable probe (and so `willContinue`) returns — set `true`
    *   to exercise the "live session" branch, `false` to exercise the re-seed
    *   path.
    * @param runResult
    *   The text `autonomous.run` echoes back.
    */
  private class StubAgentForSeeded(
      existsResult: Boolean,
      runResult: String = "ok",
      learnedWireId: Option[String] = None,
      ephemeral: Boolean = false,
      tag: Option[BackendTag] = None
  ) extends Agent[BackendTag.ClaudeCode.type]:
    val name: String = "stub-seeded"

    /** [[Agent.backendTag]] override — `None` by default (matching every
      * pre-6B.2 test's expectation), settable so a self-heal test can drive
      * `persistResumeWireId`'s tag-healing write with a concrete tag.
      */
    override private[orca] def backendTag: Option[BackendTag] = tag

    private var _capturedPrompts: List[String] = Nil

    /** The prompt the stub's most recent `run` (free-text or structured)
      * received, after preamble/seed composition.
      */
    def capturedPrompt: Option[String] = _capturedPrompts.headOption

    /** Every captured prompt in call order (oldest first) — lets a multi-run
      * test compare the first (primed) turn against a later (continued) one.
      */
    def capturedPrompts: List[String] = _capturedPrompts.reverse

    /** The durability capability the stub exposes. `ephemeral = true` builds a
      * pi-shaped `SessionSupport.ephemeral` (a STABLE instance, so an
      * in-process claim persists across runs); otherwise the durable probe
      * fixture whose `willContinue`/`resumeWireId` are driven by
      * `existsResult`/`learnedWireId`.
      */
    private val support: SessionSupport[BackendTag.ClaudeCode.type] =
      if ephemeral then
        SessionSupport.ephemeral[BackendTag.ClaudeCode.type](
          IdScheme.ClientClaimed
        )
      else stubSupport(existsResult, learnedWireId)

    /** Drives `willContinue` (via the mapping-gated probe) and `resumeWireId` —
      * `learnedWireId` mirrors a server-id backend's persist path,
      * `None`/`ephemeral` mirrors pi.
      */
    override private[orca] def sessionSupport
        : Option[SessionSupport[BackendTag.ClaudeCode.type]] =
      Some(support)

    /** Record the prompt and, for the ephemeral shape, claim the id — a real
      * ephemeral backend (pi) records the claim after a clean turn (via
      * `Conversations.drainAndCommit`), so `willContinue` flips true on the
      * next in-process run. `register` is the stable log-skip door and the id
      * is a safe UUID, so it commits.
      */
    private def capture(
        prompt: String,
        session: SessionId[BackendTag.ClaudeCode.type]
    ): Unit =
      _capturedPrompts = prompt :: _capturedPrompts
      if ephemeral then support.register(session, session.onWire)

    val autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
      new AutonomousTextCall[BackendTag.ClaudeCode.type]:
        private[orca] def runWithSession(
            prompt: String,
            session: SessionId[BackendTag.ClaudeCode.type],
            config: Option[AgentConfig],
            emitPrompt: Boolean
        )(using orca.InStage): String =
          capture(prompt, session)
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
                config: Option[AgentConfig],
                emitPrompt: Boolean
            )(using
                orca.InStage
            ): O =
              capture(summon[AgentInput[I]].serialize(input), session)
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
    val store = ProgressStore.default(dir, "p")
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.writeHeader(ProgressHeader("main", "feat/test", "deadbeef"))
    for record <- sessions do store.upsertSession(record)
    for stageName <- completedStages do
      store.appendEntry(
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
      "p"
    )

  /** A [[FlowSession]] over [[testSession]] and the given stub agent. */
  private def flowSession(
      agent: StubAgentForSeeded
  ): FlowSession[BackendTag.ClaudeCode.type] =
    new FlowSession(agent, testSession)

  // ── tests: free-text run protocol (ported from the former runSeeded) ────────

  test("live session: prompt forwarded verbatim, no preamble, no seed"):
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          occurrence = 0,
          id = testSessionId,
          seed = seed
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
    "fresh session (not exists, no completed stages): seed + prompt, no preamble"
  ):
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          occurrence = 0,
          id = testSessionId,
          seed = seed
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
          occurrence = 0,
          id = testSessionId,
          seed = "x"
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
          occurrence = 0,
          id = testSessionId,
          seed = "seed",
          resumeWireId = Some("wire-1")
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
          occurrence = 0,
          id = testSessionId,
          seed = "x"
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
          occurrence = 0,
          id = testSessionId,
          seed = seed
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
        SessionRecord(name = "s", occurrence = 0, id = testSessionId, seed = "")
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
    "pi-shaped Ephemeral session: a second in-process run does NOT re-prime"
  ):
    // pi is ephemeral: it has no durable transcript to probe, so an
    // exists-based probe would re-seed every task of a loop;
    // `willContinue` reads the in-process claim, so a live continuation runs the
    // prompt verbatim. The stub claims the id after each run (as pi's
    // drainAndCommit does), so the SECOND run must NOT re-inject seed/preamble.
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          occurrence = 0,
          id = testSessionId,
          seed = seed
        )
      )
    )
    val agent = new StubAgentForSeeded(existsResult = false, ephemeral = true)
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

  test("run returns the output from autonomous.run; .id is the session id"):
    val seed = "seed text"
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          occurrence = 0,
          id = testSessionId,
          seed = seed
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
          occurrence = 0,
          id = testSessionId,
          seed = "seed"
        )
      )
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
          occurrence = 0,
          id = testSessionId,
          seed = "seed",
          backend = None
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
      fc.progressStore.load().get.sessions.find(_.id == testSessionId).get
    assertEquals(record.resumeWireId, Some("server-thread-xyz"))
    assertEquals(
      record.backend,
      Some("ClaudeCode"),
      "an untagged recorded backend must be healed to the agent's current tag"
    )

  test(
    "run leaves resumeWireId None when the backend reports no wire id"
  ):
    // pi: ephemeral sessions, resumeWireId returns None.
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          occurrence = 0,
          id = testSessionId,
          seed = "seed"
        )
      )
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
          name = "s",
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
      sessions = List(
        SessionRecord(
          name = "s",
          occurrence = 0,
          id = testSessionId,
          seed = seed
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
      fc.progressStore.load().get.sessions.find(_.id == testSessionId).get
    assertEquals(record.resumeWireId, Some("server-structured-1"))

  test("resultAs.run on a live session forwards the input verbatim"):
    val fc = makeControl(
      sessions = List(
        SessionRecord(
          name = "s",
          occurrence = 0,
          id = testSessionId,
          seed = "seed"
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
      val session = new FlowSession(agent, testSession)
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
