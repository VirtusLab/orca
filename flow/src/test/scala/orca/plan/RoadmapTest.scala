package orca.plan

import orca.events.EventDispatcher

class RoadmapTest extends munit.FunSuite:
  private given orca.FlowContext =
    new orca.TestFlowContext(new EventDispatcher(Nil))
  private given orca.InStage = orca.InStage.unsafe

  private val storage = Epic(Title("Storage"), "Persist users")
  private val login = Epic(Title("Login"), "Password login")
  private val roadmap =
    Roadmap("User accounts", List(storage, login), "the brief")

  test("epicPrompt names the epics already done and this epic's goal"):
    val prompt = roadmap.epicPrompt(login)
    assert(prompt.contains("User accounts"), prompt)
    assert(prompt.contains("Password login"), prompt)
    assert(prompt.contains("- Storage"), prompt)
    assert(!prompt.contains("- Login"), prompt)

  test("the first epic's prompt lists no completed epics"):
    val prompt = roadmap.epicPrompt(storage)
    assert(prompt.contains("No epics are implemented yet."), prompt)
    assert(!prompt.contains("- Storage") && !prompt.contains("- Login"), prompt)

  test("autonomous.roadmap pairs the roadmap with the planning chat"):
    val canned = new CannedResult(roadmap)
    val result = Plan.autonomous.roadmap("prompt", canned.agent)
    assertEquals(result.value, roadmap)
    assertEquals(Some(result.chat.id.value), canned.lastSession)
    assertEquals(canned.lastToolSet, Some(orca.agents.ToolSet.NetworkOnly))

  /** `roadmap` on a planning chat whose agent answers `reply`. */
  private def planned(reply: CannedResult[Roadmap]): WithChat[Roadmap] =
    val chat = reply.agent.chat()
    val _ = chat.resultAs[Roadmap].autonomous.run("plan")
    WithChat(chat, roadmap)("the request")

  test("reviewed returns the revised roadmap on the planning chat"):
    val improved = roadmap.copy(description = "tighter")
    val input = planned(new CannedResult(improved))
    val result = input.reviewed()
    assertEquals(result.value, improved)
    assert(result.chat eq input.chat)

  test("reviewed critiques the roadmap with the roadmap prompt"):
    val reply = new CannedResult(roadmap)
    val input = planned(reply)
    val _ = input.reviewed()
    val critique = reply.turns(1)
    assertNotEquals(critique.session, input.chat.id.value)
    assert(critique.prompt.startsWith(PlanPrompts.RoadmapCritique))
    assert(critique.prompt.contains("the request"), critique.prompt)
    assert(critique.prompt.contains("## Epic: Storage"), critique.prompt)

  test("Announce[Roadmap] lists the epics under a header"):
    val msg = summon[orca.agents.Announce[Roadmap]].message(roadmap).get
    assert(msg.startsWith("Planned 2 epics:"), msg)
    assert(msg.contains("- Storage") && msg.contains("- Login"), msg)

  test("Announce[Roadmap] returns None for a roadmap with no epics"):
    val empty = roadmap.copy(epics = Nil)
    assertEquals(summon[orca.agents.Announce[Roadmap]].message(empty), None)
