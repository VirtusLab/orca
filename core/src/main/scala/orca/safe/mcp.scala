package orca.safe

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
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import io.circe.Decoder
import orca.json.JsonData
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint

import scala.util.control.NonFatal

/** The chimp MCP-server operations safe code uses. A tool's input is decoded
  * through its [[JsonData]] codec.
  */
@assumeSafe
object mcp:

  type Tool[I] =
    ServerTool[I, NoStructuredOutput, Identity, ServerContext[Identity]]

  def tool[I](name: String, description: String)(
      handle: I => ToolResult[NoStructuredOutput]
  )(using jd: JsonData[I]): Tool[I] =
    chimp.server
      .tool(name)
      .description(description)
      .input[I](using jd.schema, decoder(jd))
      .handle(handle)

  def text(text: String): ToolResult[NoStructuredOutput] =
    ToolResult.text(text)
  def error(message: String): ToolResult[NoStructuredOutput] =
    ToolResult.error(message)
  def fromEither(
      result: Either[String, String]
  ): ToolResult[NoStructuredOutput] = ToolResult.fromEither(result)

  /** `result` with `f` applied to each of its text contents. */
  def mapText(result: ToolResult[NoStructuredOutput])(
      f: String => String
  ): ToolResult[NoStructuredOutput] =
    result.copy(content = result.content.map:
      case t: ToolContent.Text => t.copy(text = f(t.text))
      case other               => other)

  /** An endpoint serving `tools` over MCP's HTTP transport at `/<path>`. */
  def endpoint(
      tools: List[Tool[?]],
      path: String
  ): ServerEndpoint[Any, Identity] =
    McpServer(tools = tools).endpoint(List(path))

  // jsoniter appends a multi-line buffer dump to its messages; the agent gets
  // the first line.
  private def decoder[I](jd: JsonData[I]): Decoder[I] =
    Decoder.decodeJson.emap: json =>
      try Right(readFromString[I](json.noSpaces)(using jd.codec))
      catch
        case NonFatal(e) =>
          Left(
            Option(e.getMessage)
              .flatMap(_.linesIterator.nextOption())
              .getOrElse(e.toString)
          )
