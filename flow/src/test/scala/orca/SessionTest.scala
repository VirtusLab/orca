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
import orca.progress.{ProgressHeader, ProgressStore}
import orca.tools.OsGitTool

/** Tests for `agent.session(name, seed)` get-or-create, keyed stage-style by
  * `(name, occurrence)` (ADR 0018 §2.6).
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
    val dir = os.temp.dir()
    val store = ProgressStore.default(dir, prompt)
    given InStage = InStage.unsafe
    store.writeHeader(
      ProgressHeader("main", "feat/test", "deadbeef")
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

  test(
    "first agent.session call mints a SessionId and records it at occurrence 0"
  ):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val id = agent.session("implementer", "plan brief")(using fc)
    val log = store.load().get
    assertEquals(log.sessions.size, 1)
    assertEquals(log.sessions.head.name, "implementer")
    assertEquals(log.sessions.head.occurrence, 0)
    assertEquals(log.sessions.head.seed, "plan brief")
    assertEquals(log.sessions.head.id, id.value)

  test("two calls with the same name get distinct occurrences"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val a = agent.session("reviewer", "s1")(using fc)
    val b = agent.session("reviewer", "s2")(using fc)
    assert(a.value != b.value, "distinct sessions must have different ids")
    val sessions = store.load().get.sessions
    assertEquals(sessions.size, 2)
    assertEquals(sessions(0).name, "reviewer")
    assertEquals(sessions(0).occurrence, 0)
    assertEquals(sessions(1).name, "reviewer")
    assertEquals(sessions(1).occurrence, 1)

  test("same name+occurrence resumes the recorded id across runs"):
    val (store, dir) = freshStore()
    val fc1 = makeControl(store, dir)
    val agent = new StubAgent
    val id1 = agent.session("implementer", "brief")(using fc1)

    // Simulate a second run: new FlowControl, same underlying store.
    val fc2 = makeControl(store, dir)
    val id2 = agent.session("implementer", "brief")(using fc2)

    assertEquals(id2, id1)
    // Must not mint a second record — still exactly one session.
    assertEquals(store.load().get.sessions.size, 1)

  test("an unrelated session inserted before does not re-key a named session"):
    val (store, dir) = freshStore()
    val agent = new StubAgent

    // Run 1: only "implementer" is requested.
    val implementerRun1 =
      agent.session("implementer", "brief")(using makeControl(store, dir))

    // Run 2 (fresh FlowControl, same underlying store — a resumed run whose
    // flow now starts a "planner" session first): "planner" is requested
    // before "implementer". Stage-style per-name keying means this insertion
    // must not perturb "implementer"'s identity or occurrence.
    val fc2 = makeControl(store, dir)
    val _ = agent.session("planner", "plan seed")(using fc2)
    val implementerRun2 = agent.session("implementer", "brief")(using fc2)

    assertEquals(implementerRun2, implementerRun1)
    val implementerRecord =
      store.load().get.sessions.find(_.name == "implementer").get
    assertEquals(implementerRecord.occurrence, 0)

  test("resume with a matching seed emits no divergence warning"):
    val (store, dir) = freshStore()
    val agent = new StubAgent
    val _ = agent.session("implementer", "plan brief")(using
      makeControl(
        store,
        dir
      )
    )
    val recorder = new RecordingListener
    val _ =
      agent.session("implementer", "plan brief")(using
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
    val _ = agent.session("implementer", "plan brief")(using fc)
    assertEquals(store.load().get.sessions.head.backend, Some("Codex"))

  test("first agent.session call records no backend when the agent has none"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val _ = agent.session("implementer", "plan brief")(using fc)
    assertEquals(store.load().get.sessions.head.backend, None)

  test(
    "resume with a divergent seed at the same name+occurrence warns loudly"
  ):
    // The key (name "implementer", occurrence 0) matches but the seed
    // differs — the seed was edited between runs.
    val (store, dir) = freshStore()
    val agent = new StubAgent
    val originalId =
      agent.session("implementer", "original seed")(using
        makeControl(
          store,
          dir
        )
      )
    val recorder = new RecordingListener
    val resumedId =
      agent.session("implementer", "different seed")(using
        makeControl(store, dir, List(recorder))
      )
    // Still returns the recorded id (re-seed is the safe fallback)...
    assertEquals(resumedId, originalId)
    // ...but the divergence is surfaced, naming the session and occurrence.
    assert(
      recorder.steps.exists(s =>
        s.contains("warning") && s.contains("implementer") && s.contains("#0")
      ),
      s"expected a divergence warning; got: ${recorder.steps}"
    )
