package orca

import munit.FunSuite
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}
import orca.agents.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  JsonData,
  AgentCall,
  AgentConfig,
  Agent,
  SessionId,
  ToolSet
}
import orca.progress.{BranchMode, ProgressHeader, ProgressStore, SessionRecord}
import orca.tools.OsGitTool
import orca.testkit.TempDirs

/** Tests for `agent.session(name, seed)` get-or-create, keyed by the name and
  * the stage that minted it (ADR 0018 §2.6).
  */
class SessionTest extends FunSuite:

  /** Minimal Agent stub — `session(name, seed)` is pure and never calls the
    * backend, so no methods need real implementations.
    */
  private class StubAgent extends Agent[BackendTag.ClaudeCode.type]:
    val name: String = "stub-agent"
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] = ???
    def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
    def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
    def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
    def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this

  private def freshStore(prompt: String = "p"): (ProgressStore, os.Path) =
    val dir = TempDirs.dir()
    val store = ProgressStore.default(dir, prompt)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.writeHeader(
      ProgressHeader("main", "feat/test", "deadbeef", BranchMode.Created)
    )
    (store, dir)

  private def makeControl(
      store: ProgressStore,
      dir: os.Path,
      listeners: List[OrcaListener] = Nil
  ): TestFlowControl =
    val git = new OsGitTool(dir)
    new TestFlowControl(new EventDispatcher(listeners), git, store, "p")

  /** Captures emitted `Step` messages so a test can assert on warnings. */
  private class RecordingListener extends OrcaListener:
    private val buf = scala.collection.mutable.ListBuffer.empty[String]
    def steps: List[String] = buf.toList
    def onEvent(event: OrcaEvent): Unit = event match
      case OrcaEvent.Step(msg) => buf += msg
      case _                   => ()

  private val commitMessage: Option[String => String] = Some(_ => "m")

  test("a mint outside every stage is keyed to the flow body"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val id = agent.session("implementer", seed = "plan brief")(using fc)
    val recorded = store.load().get.sessions
    assertEquals(recorded.size, 1)
    assertEquals(recorded.head.name, "implementer")
    assertEquals(recorded.head.stage, "")
    assertEquals(recorded.head.seed, "plan brief")
    assertEquals(recorded.head.id, id.id.value)

  test("a mint inside a stage is keyed to that stage's path id"):
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = fc
    val agent = new StubAgent
    val _ = stage("Task: add multiply", commitMessage):
      agent.session("implementer", seed = "brief").id.value
    val recorded = ProgressStore.default(dir, "p").load().get.sessions
    assertEquals(recorded.map(_.stage), List("Task: add multiply#0"))

  test("two stages minting one name get two sessions"):
    val (fc, _) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = fc
    val agent = new StubAgent
    def mint(): String = agent.session("implementer", seed = "s").id.value
    val a = stage("A", commitMessage)(mint())
    val b = stage("B", commitMessage)(mint())
    assertNotEquals(a, b)

  test("a re-run stage resolves its own recorded id, not a replayed stage's"):
    // The #182 shape: on resume A is replayed (its mint never runs) while B
    // re-runs. B must land back on the id IT recorded.
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    val agent = new StubAgent
    def mint()(using FlowControl): String =
      agent.session("implementer", seed = "s").id.value
    val (a, b) =
      given FlowControl = fc
      (
        stage("A", commitMessage)(mint()),
        intercept[RuntimeException](
          stage[String]("B", commitMessage):
            throw new RuntimeException(mint())
        ).getMessage
      )
    val resumedB =
      given FlowControl = reopen(dir)
      val replayedA = stage("A", commitMessage)("never runs")
      assertEquals(replayedA, a, "A must have been replayed, not re-run")
      stage("B", commitMessage)(mint())
    assertEquals(resumedB, b)
    assertNotEquals(resumedB, a)

  test("a replayed stage does not re-mint its session"):
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    val agent = new StubAgent
    val mints = new java.util.concurrent.atomic.AtomicInteger(0)
    def run()(using FlowControl): String =
      stage("Implement", commitMessage):
        val _ = mints.incrementAndGet()
        agent.session("implementer", seed = "brief").id.value
    val first = run()(using fc)
    val resumed = run()(using reopen(dir))
    assertEquals(resumed, first, "the replayed result must come from the log")
    assertEquals(mints.get(), 1, "the skipped stage's body must not run again")

  test("two mints of one name in one stage are rejected"):
    val (fc, _) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = fc
    val agent = new StubAgent
    val ex = intercept[OrcaFlowException]:
      stage[String]("Implement", commitMessage):
        val _ = agent.session("implementer", seed = "s")
        agent.session("implementer", seed = "s").id.value
    assert(
      ex.getMessage.contains("'implementer' twice in stage 'Implement'") &&
        ex.getMessage.contains("Rename one of them"),
      s"expected a duplicate message naming the stage and the fix; got: ${ex.getMessage}"
    )

  test("two mints of one name outside every stage are rejected"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val _ = agent.session("implementer", seed = "s")(using fc)
    val ex = intercept[OrcaFlowException]:
      agent.session("implementer", seed = "s")(using fc)
    assert(
      ex.getMessage.contains("twice in the flow body"),
      s"expected the flow-body wording; got: ${ex.getMessage}"
    )

  test("the same key resumes the recorded id across runs"):
    val (store, dir) = freshStore()
    val agent = new StubAgent
    val id1 = agent.session("implementer", seed = "brief")(using
      makeControl(store, dir)
    )

    // Simulate a second run: new FlowControl, same underlying store. The key
    // was minted in the previous execution, so this mint is reuse, not a
    // duplicate.
    val id2 = agent.session("implementer", seed = "brief")(using
      makeControl(store, dir)
    )

    assertEquals(id2.id, id1.id)
    // Must not mint a second record — still exactly one session.
    assertEquals(store.load().get.sessions.size, 1)

  test("a renamed stage mints a fresh session rather than resuming"):
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    val agent = new StubAgent
    def mint()(using FlowControl): String =
      agent.session("implementer", seed = "b").id.value
    val original =
      given FlowControl = fc
      stage("Task: parse the input", commitMessage)(mint())
    // A re-plan reworded the task, so the stage that owns the key is a
    // different stage: nothing recorded there to resume.
    val reworded =
      given FlowControl = reopen(dir)
      stage("Task: parse the argument", commitMessage)(mint())
    assertNotEquals(reworded, original)

  test("an unrelated session inserted before does not re-key a named session"):
    val (store, dir) = freshStore()
    val agent = new StubAgent

    // Run 1: only "implementer" is requested.
    val implementerRun1 =
      agent.session("implementer", seed = "brief")(using
        makeControl(store, dir)
      )

    // Run 2 (fresh FlowControl, same underlying store — a resumed run whose
    // flow now opens a "planner" session first): keying by the stage rather
    // than by position means this insertion must not perturb "implementer".
    val fc2 = makeControl(store, dir)
    val _ = agent.session("planner", seed = "plan seed")(using fc2)
    val implementerRun2 =
      agent.session("implementer", seed = "brief")(using fc2)

    assertEquals(implementerRun2.id, implementerRun1.id)

  test("resume with a matching seed emits no divergence warning"):
    val (store, dir) = freshStore()
    val agent = new StubAgent
    val _ = agent.session("implementer", seed = "plan brief")(using
      makeControl(store, dir)
    )
    val recorder = new RecordingListener
    val _ = agent.session("implementer", seed = "plan brief")(using
      makeControl(store, dir, List(recorder))
    )
    assert(
      !recorder.steps.exists(_.contains("warning")),
      s"no warning expected; got: ${recorder.steps}"
    )

  test("first agent.session call records the agent's backend tag"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent:
      override private[orca] def backendTag: Option[BackendTag] =
        Some(BackendTag.Codex)
    val _ = agent.session("implementer", seed = "plan brief")(using fc)
    assertEquals(store.load().get.sessions.head.backend, Some("Codex"))

  test("first agent.session call records no backend when the agent has none"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val _ = agent.session("implementer", seed = "plan brief")(using fc)
    assertEquals(store.load().get.sessions.head.backend, None)

  test("resume with a divergent seed at the same key warns loudly"):
    // The key matches but the seed differs — it was edited between runs.
    val (store, dir) = freshStore()
    val agent = new StubAgent
    val originalId = agent.session("implementer", seed = "original seed")(using
      makeControl(store, dir)
    )
    val recorder = new RecordingListener
    val resumedId = agent.session("implementer", seed = "different seed")(using
      makeControl(store, dir, List(recorder))
    )
    // Still returns the recorded id (re-seed is the safe fallback)...
    assertEquals(resumedId.id, originalId.id)
    // ...but the divergence is surfaced, naming the session.
    assert(
      recorder.steps.exists(s =>
        s.contains("warning") && s.contains("'implementer'")
      ),
      s"expected a divergence warning; got: ${recorder.steps}"
    )

  test("a warning about a session minted in a stage names that stage"):
    // A record left by an earlier run of a stage that never completed, so the
    // stage re-runs here and its mint reaches the seed-divergence warning.
    val recorder = new RecordingListener
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(List(recorder)))
    given WorkspaceWrite = WorkspaceWrite.unsafe
    ProgressStore
      .default(dir, "p")
      .upsertSession(
        SessionRecord(
          name = "implementer",
          stage = "Task: add multiply#0",
          id = SessionId.fresh[BackendTag.ClaudeCode.type].value,
          seed = "original seed"
        )
      )
    val agent = new StubAgent
    given FlowControl = fc
    val _ = stage("Task: add multiply", commitMessage):
      agent.session("implementer", seed = "edited seed").id.value
    assert(
      recorder.steps.exists(
        _.contains("'implementer' in stage 'Task: add multiply#0'")
      ),
      s"expected the stage-qualified wording; got: ${recorder.steps}"
    )

  test(
    "session() reuse with a mismatched backend tag mints fresh and warns (6B.2)"
  ):
    // First run: minted by a Codex-tagged agent.
    val (store, dir) = freshStore()
    val codexAgent = new StubAgent:
      override private[orca] def backendTag: Option[BackendTag] =
        Some(BackendTag.Codex)
    val originalId = codexAgent.session("implementer", seed = "brief")(using
      makeControl(store, dir)
    )
    assertEquals(store.load().get.sessions.head.backend, Some("Codex"))

    // Second run over the SAME key: a differently-tagged
    // agent — a lead-backend swap between runs. A backend-tag mismatch must
    // mint a fresh id and warn, not silently reuse the Codex-minted id.
    val claudeAgent = new StubAgent:
      override private[orca] def backendTag: Option[BackendTag] =
        Some(BackendTag.ClaudeCode)
    val recorder = new RecordingListener
    val resumedId = claudeAgent.session("implementer", seed = "brief")(using
      makeControl(store, dir, List(recorder))
    )

    assert(
      resumedId.id.value != originalId.id.value,
      "a backend-tag mismatch must mint a fresh id, not reuse the stale one"
    )
    assertEquals(
      store.load().get.sessions.head.backend,
      Some("ClaudeCode"),
      "the record must be re-stamped under the NEW agent's tag"
    )
    assert(
      recorder.steps.exists(s =>
        s.contains("warning") && s.contains("implementer") &&
          s.contains("Codex") && s.contains("ClaudeCode")
      ),
      s"expected a tag-mismatch warning naming both tags; got: ${recorder.steps}"
    )

  test(
    "session() reuse with BOTH a divergent seed AND a mismatched backend tag " +
      "warns only about the tag (not the seed)"
  ):
    // The record was minted by a Codex-tagged agent with one seed; the resuming
    // agent is ClaudeCode-tagged AND supplies a different seed. A fresh mint
    // follows either way (the tag mismatch alone forces it), so the seed-diff
    // warning — which claims "reusing the recorded session" — would be
    // misleading here: nothing is reused, and the edited seed is exactly what
    // ends up seeding the freshly-minted session. Only the tag-mismatch warning
    // must fire.
    val (store, dir) = freshStore()
    val codexAgent = new StubAgent:
      override private[orca] def backendTag: Option[BackendTag] =
        Some(BackendTag.Codex)
    val _ = codexAgent.session("implementer", seed = "original seed")(using
      makeControl(store, dir)
    )

    val claudeAgent = new StubAgent:
      override private[orca] def backendTag: Option[BackendTag] =
        Some(BackendTag.ClaudeCode)
    val recorder = new RecordingListener
    val _ = claudeAgent.session("implementer", seed = "different seed")(using
      makeControl(store, dir, List(recorder))
    )

    assert(
      recorder.steps.exists(s =>
        s.contains("warning") && s.contains("implementer") &&
          s.contains("Codex") && s.contains("ClaudeCode")
      ),
      s"expected a tag-mismatch warning naming both tags; got: ${recorder.steps}"
    )
    assert(
      !recorder.steps.exists(_.contains("recorded seed differs")),
      s"seed-diff warning must NOT fire when a tag mismatch already forces a " +
        s"fresh mint; got: ${recorder.steps}"
    )

  test(
    "session() reuse with a corrupted (unsafe) recorded id mints fresh and warns (6B.3)"
  ):
    // A hand-edited/corrupted log: the recorded id fails SessionId.isSafe.
    // Resuming must not trust it verbatim — parse it, and on failure mint
    // fresh exactly like the tag-mismatch and no-record cases.
    val (store, dir) = freshStore()
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.upsertSession(
      SessionRecord(
        name = "implementer",
        stage = "",
        id = "../../etc/passwd",
        seed = "brief"
      )
    )
    val agent = new StubAgent
    val recorder = new RecordingListener
    val resumedId = agent.session("implementer", seed = "brief")(using
      makeControl(store, dir, List(recorder))
    )
    assertNotEquals(resumedId.id.value, "../../etc/passwd")
    assert(
      SessionId.isSafe(resumedId.id.value),
      s"a freshly-minted id must itself be safe; got: ${resumedId.id.value}"
    )
    assert(
      recorder.steps.exists(s =>
        s.contains("warning") && s.contains("implementer") &&
          s.contains("invalid")
      ),
      s"expected an invalid-recorded-id warning; got: ${recorder.steps}"
    )

  test("an empty name is rejected"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    intercept[IllegalArgumentException]:
      agent.session("", seed = "seed")(using fc)

  test("agent.session returns a FlowSession whose .id is the recorded id"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val session: FlowSession[BackendTag.ClaudeCode.type] =
      agent.session("implementer", seed = "brief")(using fc)
    val recorded = store.load().get.sessions.head
    assertEquals(session.id.value, recorded.id)

  test("a session minted inside a stage is committed by that stage"):
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = fc
    val agent = new StubAgent
    val minted = stage("Implement"):
      agent.session("implementer", seed = "brief").id.value
    // Read through a store the stage never touched, so this sees the file the
    // stage left on disk rather than any in-memory state.
    val reread = ProgressStore.default(dir, "p").load().get
    assertEquals(reread.sessions.map(_.id), List(minted))
    assertEquals(uncommitted(dir), "", "the stage must commit the record")

  test("a re-run stage resolves its session to the recorded id"):
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    val agent = new StubAgent
    // The nested stage commits the log mid-body, so the record survives the
    // teardown reset below — the shape a stage that fails after a checkpoint
    // leaves behind.
    def mintThenCheckpoint()(using FlowControl): String =
      val id = agent.session("implementer", seed = "brief").id
      stage("Checkpoint")(())
      id.value

    val _ = intercept[RuntimeException]:
      given FlowControl = fc
      stage[String]("Implement"):
        val _ = mintThenCheckpoint()
        throw new RuntimeException("boom")
    val recorded = ProgressStore.default(dir, "p").load().get.sessions.head.id
    val _ = os.proc("git", "reset", "--hard").call(cwd = dir)

    val reMinted =
      given FlowControl = reopen(dir)
      stage("Implement")(mintThenCheckpoint())
    assertEquals(reMinted, recorded)
    assertEquals(ProgressStore.default(dir, "p").load().get.sessions.size, 1)

  /** A fresh control over the same repo and store — a re-run of the flow in a
    * new process.
    */
  private def reopen(dir: os.Path): TestFlowControl =
    new TestFlowControl(
      new EventDispatcher(Nil),
      new OsGitTool(dir),
      ProgressStore.default(dir, "p"),
      "p"
    )

  private def uncommitted(dir: os.Path): String =
    os.proc("git", "status", "--porcelain").call(cwd = dir).out.text().trim
