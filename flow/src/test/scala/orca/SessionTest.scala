package orca

import munit.FunSuite
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}
import orca.agents.{Agent, BackendTag, SessionId, WireSessionId}
import orca.backend.{Dispatch, IdScheme, ResumeOrigin, SessionSupport}
import orca.sessions.{SessionRecord, SessionStore}
import orca.tools.OsGitTool
import orca.testkit.{ScriptedBackend, TempDirs, TestAgent}

/** Tests for `agent.session(name, seed)` get-or-create, keyed by the name and
  * the stage that minted it (ADR 0018 §2.6).
  */
class SessionTest extends FunSuite:

  /** `session(name, seed)` is pure and never calls the backend. */
  private def stubAgent[B <: BackendTag & Singleton](
      tag: B
  ): Agent[B] =
    TestAgent(ScriptedBackend.unused(tag), "stub-agent")

  /** A stub over a durable, server-minting capability whose probe finds every
    * conversation, so a rehydrated wire id resumes.
    */
  private def durableStubAgent: Agent[BackendTag.ClaudeCode.type] =
    TestAgent(
      ScriptedBackend.unused(
        BackendTag.ClaudeCode,
        SessionSupport
          .durable[BackendTag.ClaudeCode.type](IdScheme.ServerMinted, _ => true)
      ),
      "stub-agent"
    )

  /** Records the `implementer` session a previous run minted and committed a
    * turn against, learning `wire`.
    */
  private def recordImplementer(dir: os.Path, wire: String): Unit =
    given WorkspaceWrite = WorkspaceWrite.unsafe
    SessionStore
      .default(dir, RunKey.of("p"))
      .upsert(
        SessionRecord(
          name = "implementer",
          stage = StagePath.FlowBody,
          id = "client-1",
          seed = "brief",
          resumeWireId = Some(wire),
          backend = BackendTag.ClaudeCode
        )
      )

  /** A flow control over `dir` — a fresh one per simulated run, as a new
    * process would build. `agent.session(...)` reads and writes only the
    * session store, so no progress header is written here; the tests that drive
    * `stage(...)` use [[TestFlowControl.create]], which writes one.
    */
  private def control(
      dir: os.Path,
      listeners: List[OrcaListener] = Nil
  ): TestFlowControl =
    new TestFlowControl(
      new TestFlowContext(
        new EventDispatcher(listeners),
        "p",
        wiredGit = Some(new OsGitTool(dir))
      ),
      orca.progress.ProgressStore.default(dir, RunKey.of("p")),
      SessionStore.default(dir, RunKey.of("p"))
    )

  private def records(dir: os.Path): List[SessionRecord] =
    SessionStore.default(dir, RunKey.of("p")).records()

  /** Captures emitted `Step` messages so a test can assert on warnings. */
  private class RecordingListener extends OrcaListener:
    private val buf = scala.collection.mutable.ListBuffer.empty[String]
    def steps: List[String] = buf.toList
    def onEvent(event: OrcaEvent): Unit = event match
      case OrcaEvent.Step(msg) => buf += msg
      case _                   => ()

  private val commitMessage: Option[String => String] = Some(_ => "m")

  /** For an outer stage whose result is the ids its inner stages minted. */
  private val idsCommitMessage: Option[List[String] => String] = Some(_ => "m")

  test("a mint outside every stage is keyed to the flow body"):
    val dir = TempDirs.dir()
    val agent = stubAgent(BackendTag.ClaudeCode)
    val session = agent.session("implementer", seed = "plan brief")(using
      control(dir)
    )
    assertEquals(
      records(dir),
      List(
        SessionRecord(
          name = "implementer",
          stage = StagePath.FlowBody,
          id = session.chat.id.value,
          seed = "plan brief",
          resumeWireId = None,
          backend = BackendTag.ClaudeCode
        )
      )
    )

  test("a mint inside a stage is keyed to that stage's path id"):
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = fc
    val agent = stubAgent(BackendTag.ClaudeCode)
    val _ = stage("Task: add multiply", commitMessage):
      agent.session("implementer", seed = "brief").chat.id.value
    assertEquals(
      records(dir).map(_.stage),
      List(StagePath.FlowBody.child("Task: add multiply", 0))
    )

  test("two stages minting one name get two sessions"):
    val (fc, _) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = fc
    val agent = stubAgent(BackendTag.ClaudeCode)
    def mint(): String = agent.session("implementer", seed = "s").chat.id.value
    val a = stage("A", commitMessage)(mint())
    val b = stage("B", commitMessage)(mint())
    assertNotEquals(a, b)

  test("a same-named stage in a loop mints one session per iteration"):
    // The shape every shipped per-task flow has, and the only one keying on the
    // path rather than the name buys: the occurrence suffix separates the
    // iterations without the author composing a per-task label.
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = fc
    val agent = stubAgent(BackendTag.ClaudeCode)
    val ids =
      for _ <- (0 until 3).toList
      yield stage("Task", commitMessage):
        agent.session("implementer", seed = "brief").chat.id.value
    assertEquals(ids.distinct.size, 3, s"expected three sessions; got: $ids")
    assertEquals(
      records(dir).map(_.stage),
      List(
        StagePath.FlowBody.child("Task", 0),
        StagePath.FlowBody.child("Task", 1),
        StagePath.FlowBody.child("Task", 2)
      )
    )

  test("each loop iteration resolves its own session when the loop resumes"):
    // Iteration 1 fails, so on resume iteration 0 replays while 1 and 2 run.
    // Each must land on the id ITS occurrence recorded, not a neighbour's.
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    val agent = stubAgent(BackendTag.ClaudeCode)
    def loop(failAt: Option[Int])(using FlowControl): List[String] =
      for i <- (0 until 3).toList
      yield stage("Task", commitMessage):
        val id = agent.session("implementer", seed = "brief").chat.id.value
        if failAt.contains(i) then throw new RuntimeException(id)
        id
    val _ = intercept[RuntimeException](loop(Some(1))(using fc))
    val recorded = records(dir).map(_.id)
    val resumed = loop(None)(using control(dir))
    assertEquals(
      resumed.take(2),
      recorded,
      "the replayed and the re-run iteration each land on their own record"
    )
    assert(
      !recorded.contains(resumed(2)),
      s"the iteration that never ran must mint fresh; got: ${resumed(2)}"
    )

  test("a mint inside a nested stage is keyed to the inner stage's path"):
    // Two inner stages, so the assertion pins both halves of the nested key:
    // the outer prefix each inner path carries, and an occurrence counter that
    // runs per scope rather than across the whole run.
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = fc
    val agent = stubAgent(BackendTag.ClaudeCode)
    val _ = stage("Implement", idsCommitMessage):
      for _ <- (0 until 2).toList
      yield stage("Task", commitMessage):
        agent.session("implementer", seed = "brief").chat.id.value
    assertEquals(
      records(dir).map(_.stage),
      List(
        StagePath.FlowBody.child("Implement", 0).child("Task", 0),
        StagePath.FlowBody.child("Implement", 0).child("Task", 1)
      )
    )

  test("an inner stage re-run on resume resolves its own recorded session"):
    // A failing inner stage takes its outer down, so the resume re-runs the
    // outer: the completed inner replays, and the failed one must land back on
    // the id recorded under its own nested path.
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    val agent = stubAgent(BackendTag.ClaudeCode)
    def run(failAt: Option[Int])(using FlowControl): List[String] =
      stage("Implement", idsCommitMessage):
        for i <- (0 until 2).toList
        yield stage("Task", commitMessage):
          val id = agent.session("implementer", seed = "brief").chat.id.value
          if failAt.contains(i) then throw new RuntimeException(id)
          id
    val _ = intercept[RuntimeException](run(Some(1))(using fc))
    val recorded = records(dir).map(_.id)
    assertEquals(
      run(None)(using control(dir)),
      recorded,
      "the replayed and the re-run inner stage each land on their own record"
    )

  test("a re-run stage does not adopt a replayed stage's session"):
    // The #182 shape: on resume A is replayed (its mint never runs) while B
    // re-runs. B must land back on the id IT recorded.
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    val agent = stubAgent(BackendTag.ClaudeCode)
    def mint()(using FlowControl): String =
      agent.session("implementer", seed = "s").chat.id.value
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
      given FlowControl = control(dir)
      val replayedA = stage("A", commitMessage)("never runs")
      assertEquals(replayedA, a, "A must have been replayed, not re-run")
      stage("B", commitMessage)(mint())
    assertEquals(resumedB, b)
    assertNotEquals(resumedB, a)

  test("two mints of one name in one stage are rejected"):
    val (fc, _) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = fc
    val agent = stubAgent(BackendTag.ClaudeCode)
    val ex = interceptReported[OrcaFlowException]:
      stage[String]("Implement", commitMessage):
        val _ = agent.session("implementer", seed = "s")
        agent.session("implementer", seed = "s").chat.id.value
    assert(
      ex.getMessage.contains("'implementer' twice in stage 'Implement#0'") &&
        ex.getMessage.contains("Give each its own `stage(...)`"),
      s"expected a duplicate message naming the stage and the fix; got: ${ex.getMessage}"
    )

  test("two mints of one name outside every stage are rejected"):
    val dir = TempDirs.dir()
    val fc = control(dir)
    val agent = stubAgent(BackendTag.ClaudeCode)
    val _ = agent.session("implementer", seed = "s")(using fc)
    val ex = intercept[OrcaFlowException]:
      agent.session("implementer", seed = "s")(using fc)
    assert(
      ex.getMessage.contains("twice in the flow body"),
      s"expected the flow-body wording; got: ${ex.getMessage}"
    )

  test("the same key resumes the recorded id across runs"):
    val dir = TempDirs.dir()
    val agent = stubAgent(BackendTag.ClaudeCode)
    val id1 = agent.session("implementer", seed = "brief")(using control(dir))

    // Simulate a second run: new FlowControl, same underlying store. The key
    // was minted in the previous execution, so this mint is reuse, not a
    // duplicate.
    val id2 = agent.session("implementer", seed = "brief")(using control(dir))

    assertEquals(id2.chat.id.value, id1.chat.id.value)
    // Must not mint a second record — still exactly one session.
    assertEquals(records(dir).size, 1)

  test("a renamed stage mints a fresh session rather than resuming"):
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    val agent = stubAgent(BackendTag.ClaudeCode)
    def mint()(using FlowControl): String =
      agent.session("implementer", seed = "b").chat.id.value
    val original =
      given FlowControl = fc
      stage("Task: parse the input", commitMessage)(mint())
    // A re-plan reworded the task, so the stage that owns the key is a
    // different stage: nothing recorded there to resume.
    val reworded =
      given FlowControl = control(dir)
      stage("Task: parse the argument", commitMessage)(mint())
    assertNotEquals(reworded, original)

  test("an unrelated session inserted before does not re-key a named session"):
    val dir = TempDirs.dir()
    val agent = stubAgent(BackendTag.ClaudeCode)

    // Run 1: only "implementer" is requested.
    val implementerRun1 =
      agent.session("implementer", seed = "brief")(using control(dir))

    // Run 2 (fresh FlowControl, same underlying store — a resumed run whose
    // flow now opens a "planner" session first): keying by the stage rather
    // than by position means this insertion must not perturb "implementer".
    val fc2 = control(dir)
    val _ = agent.session("planner", seed = "plan seed")(using fc2)
    val implementerRun2 =
      agent.session("implementer", seed = "brief")(using fc2)

    assertEquals(implementerRun2.chat.id.value, implementerRun1.chat.id.value)

  test("resume with a matching seed emits no divergence warning"):
    val dir = TempDirs.dir()
    val agent = stubAgent(BackendTag.ClaudeCode)
    val _ =
      agent.session("implementer", seed = "plan brief")(using control(dir))
    val recorder = new RecordingListener
    val _ = agent.session("implementer", seed = "plan brief")(using
      control(dir, List(recorder))
    )
    assert(
      !recorder.steps.exists(_.contains("warning")),
      s"no warning expected; got: ${recorder.steps}"
    )

  test("first agent.session call records the agent's backend tag"):
    val dir = TempDirs.dir()
    val agent = stubAgent(BackendTag.Codex)
    val _ =
      agent.session("implementer", seed = "plan brief")(using control(dir))
    assertEquals(records(dir).head.backend, BackendTag.Codex)

  test("resume with a divergent seed at the same key warns loudly"):
    // The key matches but the seed differs — it was edited between runs.
    val dir = TempDirs.dir()
    val agent = stubAgent(BackendTag.ClaudeCode)
    val originalId =
      agent.session("implementer", seed = "original seed")(using control(dir))
    val recorder = new RecordingListener
    val resumedId = agent.session("implementer", seed = "different seed")(using
      control(dir, List(recorder))
    )
    // Still returns the recorded id (re-seed is the safe fallback)...
    assertEquals(resumedId.chat.id.value, originalId.chat.id.value)
    // ...but the divergence is surfaced, naming the session.
    assert(
      recorder.steps.exists(s =>
        s.contains("warning") && s.contains("'implementer'")
      ),
      s"expected a divergence warning; got: ${recorder.steps}"
    )

  test(
    "session() reuse with a mismatched backend tag mints fresh and warns (6B.2)"
  ):
    // First run: minted by a Codex-tagged agent.
    val dir = TempDirs.dir()
    val codexAgent = stubAgent(BackendTag.Codex)
    val originalId =
      codexAgent.session("implementer", seed = "brief")(using control(dir))
    assertEquals(records(dir).head.backend, BackendTag.Codex)

    // Second run over the SAME key: a differently-tagged
    // agent — a lead-backend swap between runs. A backend-tag mismatch must
    // mint a fresh id and warn, not silently reuse the Codex-minted id.
    val claudeAgent = stubAgent(BackendTag.ClaudeCode)
    val recorder = new RecordingListener
    val resumedId = claudeAgent.session("implementer", seed = "brief")(using
      control(dir, List(recorder))
    )

    assert(
      resumedId.chat.id.value != originalId.chat.id.value,
      "a backend-tag mismatch must mint a fresh id, not reuse the stale one"
    )
    assertEquals(
      records(dir).head.backend,
      BackendTag.ClaudeCode,
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
    val dir = TempDirs.dir()
    val codexAgent = stubAgent(BackendTag.Codex)
    val _ =
      codexAgent.session("implementer", seed = "original seed")(using
        control(dir)
      )

    val claudeAgent = stubAgent(BackendTag.ClaudeCode)
    val recorder = new RecordingListener
    val _ = claudeAgent.session("implementer", seed = "different seed")(using
      control(dir, List(recorder))
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
    // A hand-edited/corrupted store: the recorded id fails SessionId.isSafe.
    // Resuming must not trust it verbatim — parse it, and on failure mint
    // fresh exactly like the tag-mismatch and no-record cases.
    val dir = TempDirs.dir()
    given WorkspaceWrite = WorkspaceWrite.unsafe
    SessionStore
      .default(dir, RunKey.of("p"))
      .upsert(
        SessionRecord(
          name = "implementer",
          stage = StagePath.FlowBody,
          id = "../../etc/passwd",
          seed = "brief",
          resumeWireId = None,
          backend = BackendTag.ClaudeCode
        )
      )
    val agent = stubAgent(BackendTag.ClaudeCode)
    val recorder = new RecordingListener
    val resumedId =
      agent.session("implementer", seed = "brief")(using
        control(dir, List(recorder))
      )
    assertNotEquals(resumedId.chat.id.value, "../../etc/passwd")
    assert(
      SessionId.isSafe(resumedId.chat.id.value),
      s"a freshly-minted id must itself be safe; got: ${resumedId.chat.id.value}"
    )
    assert(
      recorder.steps.exists(s =>
        s.contains("warning") && s.contains("implementer") &&
          s.contains("invalid")
      ),
      s"expected an invalid-recorded-id warning; got: ${recorder.steps}"
    )

  test("an empty name is rejected"):
    val agent = stubAgent(BackendTag.ClaudeCode)
    intercept[IllegalArgumentException]:
      agent.session("", seed = "seed")(using control(TempDirs.dir()))

  test("no stage commits a session record"):
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = fc
    val agent = stubAgent(BackendTag.ClaudeCode)
    val minted = stage("Implement", commitMessage):
      agent.session("implementer", seed = "brief").chat.id.value
    assertEquals(records(dir).map(_.id), List(minted))
    // The record is machine-local: it lands in the self-ignoring cache, so the
    // stage's commit carries nothing of it and the tree stays clean.
    assertEquals(uncommitted(dir), "", "the record must not be a tree change")
    assert(
      !tracked(dir).exists(_.startsWith(".orca/cache/")),
      s"nothing under .orca/cache may be tracked; got: ${tracked(dir)}"
    )

  test("a session minted by a failed stage survives the teardown reset"):
    // The reuse branch's whole reason to exist: the stage a resume re-runs is
    // exactly the stage that failed, and failure teardown resets the tree.
    val (fc, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    val agent = stubAgent(BackendTag.ClaudeCode)
    def mint()(using FlowControl): String =
      agent.session("implementer", seed = "brief").chat.id.value

    val firstAttempt = intercept[RuntimeException]:
      given FlowControl = fc
      // A stage that mints and COMPLETES first, so the reset below has a
      // committed state to revert to — the shape that erased a record kept in
      // the log, where the failing stage's write was the uncommitted delta.
      val _ = stage("Plan", commitMessage):
        agent.session("planner", seed = "brief").chat.id.value
      stage[String]("Implement", commitMessage):
        throw new RuntimeException(mint())
    new OsGitTool(dir).discardUncommitted(orca.tools.UntrackedFiles.Remove)(
      using WorkspaceWrite.unsafe
    )

    val reMinted =
      given FlowControl = control(dir)
      stage("Implement", commitMessage)(mint())
    assertEquals(reMinted, firstAttempt.getMessage)
    assertEquals(
      records(dir).count(_.name == "implementer"),
      1,
      "the re-run must reuse the record, not append a second"
    )

  private def uncommitted(dir: os.Path): String =
    os.proc("git", "status", "--porcelain").call(cwd = dir).out.text().trim

  private def tracked(dir: os.Path): List[String] =
    os.proc("git", "ls-files").call(cwd = dir).out.lines().toList

  test("reusing a recorded session resumes against its recorded wire id"):
    val dir = TempDirs.dir()
    recordImplementer(dir, wire = "srv-1")
    val agent = durableStubAgent
    val session =
      agent.session("implementer", seed = "brief")(using control(dir))
    assertEquals(
      agent.dispatchFor(SessionId(session.chat.id.value)),
      Dispatch.Resume(
        WireSessionId[BackendTag.ClaudeCode.type]("srv-1"),
        ResumeOrigin.EarlierAttempt
      )
    )

  test(
    "reusing a session with an unsafe recorded wire id warns and opens fresh"
  ):
    val dir = TempDirs.dir()
    recordImplementer(dir, wire = ".*")
    val agent = durableStubAgent
    val recorder = new RecordingListener
    val session = agent.session("implementer", seed = "brief")(using
      control(dir, List(recorder))
    )
    assertEquals(
      agent.dispatchFor(SessionId(session.chat.id.value)),
      Dispatch.Fresh(None)
    )
    assert(
      recorder.steps.exists(_.contains("invalid recorded wire id")),
      s"expected an invalid-wire warning; got: ${recorder.steps}"
    )
