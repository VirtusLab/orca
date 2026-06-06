package orca.tools.opencode

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.tools.opencode.OpencodeApi.*
import orca.util.RawJson

class OpencodeApiTest extends munit.FunSuite:

  test("MessageBody serialises parts, model, system, tools, and format"):
    val body = MessageBody(
      parts = List(MessagePart("text", "do it")),
      model = Some(ModelRef("openai", "gpt-4o-mini")),
      system = Some("be brief"),
      tools = Some(Map("question" -> false)),
      format =
        Some(OutputFormat("json_schema", RawJson("""{"type":"object"}""")))
    )
    val json = writeToString(body)
    assert(json.contains(""""text":"do it""""), json)
    assert(json.contains(""""providerID":"openai""""), json)
    assert(json.contains(""""modelID":"gpt-4o-mini""""), json)
    assert(json.contains(""""system":"be brief""""), json)
    assert(json.contains(""""question":false"""), json)
    assert(json.contains(""""type":"json_schema""""), json)
    assert(json.contains(""""schema":{"type":"object"}"""), json)

  test("MessageBody omits absent optional fields"):
    val body = MessageBody(
      parts = List(MessagePart("text", "hi")),
      model = Some(ModelRef("openai", "gpt-4o-mini"))
    )
    val json = writeToString(body)
    assert(!json.contains("system"), json)
    assert(!json.contains("format"), json)
    assert(!json.contains("tools"), json)

  test("reply bodies serialise to the server's expected shape"):
    assertEquals(
      writeToString(QuestionReplyBody(List(List("Blue")))),
      """{"answers":[["Blue"]]}"""
    )
    assertEquals(
      writeToString(PermissionReplyBody("once")),
      """{"reply":"once"}"""
    )

  test("SessionCreated reads the server-allocated id"):
    assertEquals(
      readFromString[SessionCreated](
        """{"id":"ses_X","title":"t","time":{"created":1}}"""
      ),
      SessionCreated("ses_X")
    )
