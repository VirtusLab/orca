package orca.backend.mcp

import chimp.tool
import io.circe.Codec
import sttp.tapir.Schema

private case class Probe(text: String) derives Codec, Schema

class McpHostTest extends munit.FunSuite:

  /** What a tool with this logic returns once bound through [[McpHost]] — the
    * guard, not the handler, is what these tests are about.
    */
  private def resultOf(
      logic: Probe => Either[String, String]
  ): Either[String, String] =
    McpHost
      .guarded(tool("probe").input[Probe].handle(logic))
      .logic(Probe(""), Nil)

  test("a result of exactly the cap passes through whole"):
    val text = "x" * McpHost.MaxOutputChars
    assertEquals(resultOf(_ => Right(text)), Right(text))

  test("a result one char over the cap is cut, and names the cut"):
    val out =
      resultOf(_ => Right("x" * (McpHost.MaxOutputChars + 1))).toOption.get
    assert(out.startsWith("x" * McpHost.MaxOutputChars), out.take(80))
    assert(out.endsWith("characters — narrow the request]"), out.takeRight(80))

  test("an error over the cap is cut too: it costs the same tokens"):
    val out =
      resultOf(_ => Left("x" * (McpHost.MaxOutputChars + 1))).left.toOption.get
    assert(out.endsWith("characters — narrow the request]"), out.takeRight(80))

  test("a handler that throws returns a tool error rather than escaping"):
    assertEquals(
      resultOf(_ => throw new RuntimeException("gh exploded")),
      Left("gh exploded")
    )
