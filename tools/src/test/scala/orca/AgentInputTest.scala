package orca

import orca.agents.{AgentInput, JsonData}

case class User(name: String, age: Int) derives JsonData

class AgentInputTest extends munit.FunSuite:
  test("string input is serialized as-is"):
    val input = summon[AgentInput[String]]
    assertEquals(input.serialize("hello"), "hello")

  test("case class input is serialized as JSON"):
    val input = summon[AgentInput[User]]
    assertEquals(
      input.serialize(User("Alice", 30)),
      """{"name":"Alice","age":30}"""
    )
