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

  test("reviewed continues the roadmap chat read-only"):
    val improved = roadmap.copy(description = "tighter")
    val reply = new CannedResult(improved)
    val chat = reply.agent.chat()
    val _ = chat.resultAs[Roadmap].autonomous.run("plan")
    val result = WithChat(chat, roadmap).reviewed()
    assertEquals(result.value, improved)
    assert(result.chat eq chat)
    assertEquals(reply.lastSession, Some(chat.id.value))
    assertEquals(reply.lastToolSet, Some(orca.agents.ToolSet.ReadOnly))

  test("reviewed runs its variant of the read-only agent"):
    val reply = new CannedResult(roadmap)
    val onlyReads = orca.agents.AutoApprove.Only(Set("Read"))
    val _ = WithChat(reply.agent.chat(), roadmap)
      .reviewed(variant = _.withAutoApprove(onlyReads))
    assertEquals(reply.lastAutoApprove, Some(onlyReads))
    assertEquals(reply.lastToolSet, Some(orca.agents.ToolSet.ReadOnly))

  test("Announce[Roadmap] lists the epics under a header"):
    val msg = summon[orca.agents.Announce[Roadmap]].message(roadmap).get
    assert(msg.startsWith("Planned 2 epics:"), msg)
    assert(msg.contains("- Storage") && msg.contains("- Login"), msg)

  test("Announce[Roadmap] returns None for a roadmap with no epics"):
    val empty = roadmap.copy(epics = Nil)
    assertEquals(summon[orca.agents.Announce[Roadmap]].message(empty), None)
