package orca

import munit.FunSuite
import orca.events.EventDispatcher
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

/** Tests for `llm.session(seed)` get-or-create (ADR 0018 §2.6). */
class SessionTest extends FunSuite:

  /** Minimal Agent stub — `session(seed)` is pure and never calls the backend,
    * so no methods need real implementations.
    */
  private class StubAgent extends Agent[BackendTag.ClaudeCode.type]:
    val name: String = "stub-llm"
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

  private def makeControl(store: ProgressStore, dir: os.Path): TestFlowControl =
    val git = new OsGitTool(dir)
    new TestFlowControl(new EventDispatcher(Nil), git, store, "p")

  test("first llm.session call mints a SessionId and records it at index 0"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val llm = new StubAgent
    val id = llm.session("plan brief")(using fc)
    val log = store.load().get
    assertEquals(log.sessions.size, 1)
    assertEquals(log.sessions.head.index, 0)
    assertEquals(log.sessions.head.seed, "plan brief")
    assertEquals(log.sessions.head.id, id.value)

  test("second llm.session call mints a separate id at index 1"):
    val (store, dir) = freshStore()
    val fc = makeControl(store, dir)
    val llm = new StubAgent
    val id0 = llm.session("seed zero")(using fc)
    val id1 = llm.session("seed one")(using fc)
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
    val llm = new StubAgent
    val originalId = llm.session("plan brief")(using fc1)

    // Simulate a second run: new FlowControl, same underlying store.
    val fc2 = makeControl(store, dir)
    val resumedId = llm.session("plan brief")(using fc2)

    assertEquals(resumedId, originalId)
    // Must not mint a second record — still exactly one session.
    assertEquals(store.load().get.sessions.size, 1)
