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

/** Tests for `agent.session(seed)` get-or-create (ADR 0018 §2.6). */
class SessionTest extends FunSuite:

  /** Minimal Agent stub — `session(seed)` is pure and never calls the backend,
    * so no methods need real implementations.
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

  test("first agent.session call mints a SessionId and records it at index 0"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val id = agent.session("plan brief")(using fc)
    val log = store.load().get
    assertEquals(log.sessions.size, 1)
    assertEquals(log.sessions.head.index, 0)
    assertEquals(log.sessions.head.seed, "plan brief")
    assertEquals(log.sessions.head.id, id.value)

  test("second agent.session call mints a separate id at index 1"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val id0 = agent.session("seed zero")(using fc)
    val id1 = agent.session("seed one")(using fc)
    assert(id0.value != id1.value, "distinct sessions must have different ids")
    val sessions = store.load().get.sessions
    assertEquals(sessions.size, 2)
    assertEquals(sessions(0).index, 0)
    assertEquals(sessions(1).index, 1)

  test(
    "resume: a fresh FlowControl over the same store returns the recorded id"
  ):
    val (store, dir) = freshStore()
    val fc1 = makeControl(store, dir)
    val agent = new StubAgent
    val originalId = agent.session("plan brief")(using fc1)

    // Simulate a second run: new FlowControl, same underlying store.
    val fc2 = makeControl(store, dir)
    val resumedId = agent.session("plan brief")(using fc2)

    assertEquals(resumedId, originalId)
    // Must not mint a second record — still exactly one session.
    assertEquals(store.load().get.sessions.size, 1)

  test("resume with a matching seed emits no divergence warning"):
    val (store, dir) = freshStore()
    val agent = new StubAgent
    val _ = agent.session("plan brief")(using makeControl(store, dir))
    val recorder = new RecordingListener
    val _ =
      agent.session("plan brief")(using makeControl(store, dir, List(recorder)))
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
    val _ = agent.session("plan brief")(using fc)
    assertEquals(store.load().get.sessions.head.backend, Some("Codex"))

  test("first agent.session call records no backend when the agent has none"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val agent = new StubAgent
    val _ = agent.session("plan brief")(using fc)
    assertEquals(store.load().get.sessions.head.backend, None)

  test("resume with a divergent seed at the same index warns loudly"):
    // The positional key (index 0) matches but the seed differs — the most
    // likely symptom of a shifted `session(...)` call sequence.
    val (store, dir) = freshStore()
    val agent = new StubAgent
    val originalId =
      agent.session("original seed")(using makeControl(store, dir))
    val recorder = new RecordingListener
    val resumedId =
      agent.session("different seed")(using
        makeControl(store, dir, List(recorder))
      )
    // Still returns the recorded id (re-seed is the safe fallback)...
    assertEquals(resumedId, originalId)
    // ...but the divergence is surfaced.
    assert(
      recorder.steps.exists(s => s.contains("warning") && s.contains("#0")),
      s"expected a divergence warning; got: ${recorder.steps}"
    )
