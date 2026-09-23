package orca.agents

import orca.backend.{AgentResult, TurnRequest}
import orca.testkit.{ScriptedBackend, TestAgent}

/** Which model [[Agent.cheap]] resolves to. */
class AgentCheapTest extends munit.FunSuite:

  test("a backend without a cheap tier keeps the agent's own model"):
    val agent = TestAgent(ScriptedBackend.unused(BackendTag.Pi))
      .withModel(Model("lead"))
    assertEquals(agent.cheap.config.model, Some(Model("lead")))

  test("the backend's cheap tier is pinned when none is set"):
    val agent = TestAgent(TieredBackend).withModel(Model("lead"))
    assertEquals(agent.cheap.config.model, Some(Model("tier-for-lead")))

  test("withCheapModel overrides the backend's cheap tier"):
    val agent = TestAgent(TieredBackend).withCheapModel(Model("cheap-x"))
    assertEquals(agent.cheap.config.model, Some(Model("cheap-x")))

  test("the withCheapModel pin survives other builders"):
    val agent = TestAgent(TieredBackend)
      .withCheapModel(Model("cheap-x"))
      .withReadOnly
    assertEquals(agent.cheap.config.model, Some(Model("cheap-x")))

  test("cheap keeps the agent's name and role"):
    val agent = TestAgent(TieredBackend).withName("coder").withRole("coding")
    assertEquals(
      (agent.cheap.name, agent.cheap.role),
      ("coder", Some("coding"))
    )

  /** Derives its cheap tier from the leading model, so the argument is
    * observable.
    */
  private object TieredBackend extends ScriptedBackend(BackendTag.Pi):
    override def cheapModel(leading: Option[Model]): Option[Model] =
      Some(Model(s"tier-for-${leading.fold("none")(Model.name)}"))
    protected def reply(
        turn: TurnRequest[BackendTag.Pi.type]
    ): AgentResult[BackendTag.Pi.type] = ???
