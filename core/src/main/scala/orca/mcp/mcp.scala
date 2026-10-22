package orca.mcp

import language.experimental.captureChecking

import caps.assumeSafe
import chimp.protocol.ToolContent
import chimp.server.{
  McpServer,
  NoStructuredOutput,
  ServerContext,
  ServerTool,
  ToolResult
}
import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  readFromString
}
import io.circe.Decoder
import orca.json.JsonData
import sttp.shared.Identity
import ox.either.catching
import sttp.tapir.server.ServerEndpoint

// The chimp MCP-server operations safe code uses.

@assumeSafe
type Tool[I] =
  ServerTool[I, NoStructuredOutput, Identity, ServerContext[Identity]]

/** A tool whose input is decoded through its [[JsonData]] codec. */
@assumeSafe
def tool[I](name: String, description: String)(
    handle: I => ToolResult[NoStructuredOutput]
)(using jd: JsonData[I]): Tool[I] =
  chimp.server
    .tool(name)
    .description(description)
    .input[I](using jd.schema, decoder(jd))
    .handle(handle)

@assumeSafe
def text(text: String): ToolResult[NoStructuredOutput] =
  ToolResult.text(text)
@assumeSafe
def error(message: String): ToolResult[NoStructuredOutput] =
  ToolResult.error(message)
@assumeSafe
def fromEither(
    result: Either[String, String]
): ToolResult[NoStructuredOutput] = ToolResult.fromEither(result)

/** `result` with `f` applied to each of its text contents. */
@assumeSafe
def mapText(result: ToolResult[NoStructuredOutput])(
    f: String => String
): ToolResult[NoStructuredOutput] =
  result.copy(content = result.content.map:
    case t: ToolContent.Text => t.copy(text = f(t.text))
    case other               => other)

/** The path [[endpoint]] serves at. */
@assumeSafe
val endpointPath: String = "mcp"

/** An endpoint serving `tools` over MCP's HTTP transport at
  * `/`[[endpointPath]].
  */
@assumeSafe
def endpoint(tools: List[Tool[?]]): ServerEndpoint[Any, Identity] =
  McpServer(tools = tools).endpoint(List(endpointPath))

// jsoniter appends a multi-line buffer dump to its messages; the agent gets
// the first line.
private def decoder[I](jd: JsonData[I]): Decoder[I] =
  Decoder.decodeJson.emap: json =>
    readFromString[I](json.noSpaces)(using jd.codec)
      .catching[JsonReaderException]
      .left
      .map(e =>
        Option(e.getMessage)
          .flatMap(_.linesIterator.nextOption())
          .getOrElse(e.toString)
      )
