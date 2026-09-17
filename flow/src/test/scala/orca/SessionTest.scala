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

/** Tests for `agent.session(name, detail, seed)` get-or-create, keyed by
  * `(name, detail)` (ADR 0018 §2.6).
  */
class SessionTest extends FunSuite:

  /** Minimal Agent stub — `session(name, detail, seed)` is pure and never calls
    * the backend, so no methods need real implementations.
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

  test("first agent.session call records the session under its key"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val id =
      agent.session("implementer", detail = "task 1", seed = "plan brief")(using
        fc
      )
    val log = store.load().get
    assertEquals(log.sessions.size, 1)
    assertEquals(log.sessions.head.name, "implementer")
    assertEquals(log.sessions.head.detail, "task 1")
    assertEquals(log.sessions.head.seed, "plan brief")
    assertEquals(log.sessions.head.id, id.id.value)

  test("two details under one name are two sessions"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val a =
      agent.session("implementer", detail = "task 1", seed = "s")(using fc)
    val b =
      agent.session("implementer", detail = "task 2", seed = "s")(using fc)
    assert(
      a.id.value != b.id.value,
      "distinct sessions must have different ids"
    )
    val sessions = store.load().get.sessions
    assertEquals(
      sessions.map(r => (r.name, r.detail)).toSet,
      Set(("implementer", "task 1"), ("implementer", "task 2"))
    )

  test("the same key resumes the recorded id across runs"):
    val (store, dir) = freshStore()
    val agent = new StubAgent
    val id1 = agent.session("implementer", detail = "task 1", seed = "brief")(
      using makeControl(store, dir)
    )

    // Simulate a second run: new FlowControl, same underlying store. The key
    // was minted in the previous execution, so this mint is reuse, not a
    // duplicate.
    val id2 = agent.session("implementer", detail = "task 1", seed = "brief")(
      using makeControl(store, dir)
    )

    assertEquals(id2.id, id1.id)
    // Must not mint a second record — still exactly one session.
    assertEquals(store.load().get.sessions.size, 1)

  test("minting one key twice in a single run is rejected"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val _ =
      agent.session("implementer", detail = "task 1", seed = "s")(using fc)
    val ex = intercept[OrcaFlowException]:
      agent.session("implementer", detail = "task 1", seed = "s")(using fc)
    assert(
      ex.getMessage.contains("implementer (task 1)") &&
        ex.getMessage.contains("detail"),
      s"expected a duplicate-key message naming the key and the fix; got: ${ex.getMessage}"
    )

  test("a changed detail mints a fresh session rather than resuming"):
    val (store, dir) = freshStore()
    val agent = new StubAgent
    val original = agent.session("implementer", detail = "task 1", seed = "b")(
      using makeControl(store, dir)
    )
    // A re-plan reworded the task: a different key, so nothing to resume.
    val reworded =
      agent.session("implementer", detail = "task one", seed = "b")(using
        makeControl(store, dir)
      )
    assert(
      reworded.id.value != original.id.value,
      "a reworded detail must not resume the old session"
    )

  test("an unrelated session inserted before does not re-key a named session"):
    val (store, dir) = freshStore()
    val agent = new StubAgent

    // Run 1: only "implementer" is requested.
    val implementerRun1 =
      agent.session("implementer", detail = "task 1", seed = "brief")(using
        makeControl(store, dir)
      )

    // Run 2 (fresh FlowControl, same underlying store — a resumed run whose
    // flow now starts a "planner" session first): "planner" is requested
    // before "implementer". Keying by (name, detail) means this insertion must
    // not perturb "implementer"'s identity.
    val fc2 = makeControl(store, dir)
    val _ = agent.session("planner", detail = "the plan", seed = "plan seed")(
      using fc2
    )
    val implementerRun2 =
      agent.session("implementer", detail = "task 1", seed = "brief")(using fc2)

    assertEquals(implementerRun2.id, implementerRun1.id)

  test("resume with a matching seed emits no divergence warning"):
    val (store, dir) = freshStore()
    val agent = new StubAgent
    val _ =
      agent.session("implementer", detail = "task 1", seed = "plan brief")(using
        makeControl(
          store,
          dir
        )
      )
    val recorder = new RecordingListener
    val _ =
      agent.session("implementer", detail = "task 1", seed = "plan brief")(using
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
    val _ =
      agent.session("implementer", detail = "task 1", seed = "plan brief")(using
        fc
      )
    assertEquals(store.load().get.sessions.head.backend, Some("Codex"))

  test("first agent.session call records no backend when the agent has none"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val _ =
      agent.session("implementer", detail = "task 1", seed = "plan brief")(using
        fc
      )
    assertEquals(store.load().get.sessions.head.backend, None)

  test("resume with a divergent seed at the same key warns loudly"):
    // The key (name "implementer", detail "task 1") matches but the seed
    // differs — the seed was edited between runs.
    val (store, dir) = freshStore()
    val agent = new StubAgent
    val originalId =
      agent.session("implementer", detail = "task 1", seed = "original seed")(
        using makeControl(store, dir)
      )
    val recorder = new RecordingListener
    val resumedId =
      agent.session("implementer", detail = "task 1", seed = "different seed")(
        using makeControl(store, dir, List(recorder))
      )
    // Still returns the recorded id (re-seed is the safe fallback)...
    assertEquals(resumedId.id, originalId.id)
    // ...but the divergence is surfaced, naming the session.
    assert(
      recorder.steps.exists(s =>
        s.contains("warning") && s.contains("implementer (task 1)")
      ),
      s"expected a divergence warning; got: ${recorder.steps}"
    )

  test(
    "session() reuse with a mismatched backend tag mints fresh and warns (6B.2)"
  ):
    // First run: minted by a Codex-tagged agent.
    val (store, dir) = freshStore()
    val codexAgent = new StubAgent:
      override private[orca] def backendTag: Option[BackendTag] =
        Some(BackendTag.Codex)
    val originalId =
      codexAgent.session("implementer", detail = "task 1", seed = "brief")(using
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
    val resumedId =
      claudeAgent.session("implementer", detail = "task 1", seed = "brief")(
        using makeControl(store, dir, List(recorder))
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
    val _ =
      codexAgent.session(
        "implementer",
        detail = "task 1",
        seed = "original seed"
      )(using
        makeControl(store, dir)
      )

    val claudeAgent = new StubAgent:
      override private[orca] def backendTag: Option[BackendTag] =
        Some(BackendTag.ClaudeCode)
    val recorder = new RecordingListener
    val _ =
      claudeAgent.session(
        "implementer",
        detail = "task 1",
        seed = "different seed"
      )(using
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
        detail = "task 1",
        id = "../../etc/passwd",
        seed = "brief"
      )
    )
    val agent = new StubAgent
    val recorder = new RecordingListener
    val resumedId =
      agent.session("implementer", detail = "task 1", seed = "brief")(using
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
      agent.session("", detail = "task 1", seed = "seed")(using fc)

  test("agent.session returns a FlowSession whose .id is the recorded id"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val session: FlowSession[BackendTag.ClaudeCode.type] =
      agent.session("implementer", detail = "task 1", seed = "brief")(using fc)
    val recorded = store.load().get.sessions.head
    assertEquals(session.id.value, recorded.id)

  // Minting outside a stage is exercised by every other test in this suite;
  // the two tests below pin the complementary guard's two layers: minting
  // inside a stage is rejected (a skipped stage on resume would never re-mint)
  // — at compile time for the direct call, at runtime for the indirect one
  // OutsideStage can't see.
  test("agent.session directly inside a stage body does not compile"):
    val errors = compileErrors(
      """
      given FlowControl = ???
      given orca.InStage = orca.InStage.unsafe
      val agent = new StubAgent
      val _ = agent.session("implementer", detail = "task 1", seed = "seed")
      """
    )
    assert(
      errors.contains("must be called outside a stage"),
      s"expected the OutsideStage implicitNotFound message, got: $errors"
    )

  test(
    "agent.session minted inside a stage via a FlowControl-only helper is rejected at runtime"
  ):
    val (store, dir) = freshStore()
    given FlowControl = makeControl(store, dir)
    val agent = new StubAgent
    def mintInHelper()(using FlowControl): Unit =
      val _ = agent.session("implementer", detail = "task 1", seed = "seed")
    intercept[OrcaFlowException]:
      stage("outer"):
        mintInHelper()
        "done"
