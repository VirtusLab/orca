package orca.backend.mcp

import chimp.server.{NoStructuredOutput, ServerContext, ServerTool, ToolResult}
import orca.effect.Network
import orca.safe.mcp
import ox.Ox
import sttp.shared.Identity
import orca.safe.prelude.NonFatal

import scala.concurrent.duration.FiniteDuration

/** One MCP server orca stands up for a single agent turn: a Netty binding on
  * `127.0.0.1` at an ephemeral port, serving whatever tools it was started
  * with.
  *
  * Lives as long as the scope [[McpHost.start]] ran in — the turn, not the
  * backend, so a long flow doesn't accumulate bindings.
  */
private[backend] class McpHost private[mcp] (val port: Int):

  /** The URL an MCP client (claude's `.mcp.json`, codex's
    * `mcp_servers.<name>.url`) should target.
    */
  val url: String = s"${McpHost.UrlPrefix}$port/mcp"

private[backend] object McpHost:

  private val UrlPrefix: String = "http://127.0.0.1:"

  /** Whether `url` has the form of an [[McpHost.url]]. */
  private[orca] def isHostUrl(url: String): Boolean = url.startsWith(UrlPrefix)

  /** Chars any one tool result returns, on either channel. Past this the tail
    * is dropped and the result says so, so a single call costs a bounded number
    * of tokens rather than the turn's whole context.
    */
  private[mcp] val MaxOutputChars: Int = 60000

  private val CutMarker: String =
    s"\n\n[cut after $MaxOutputChars characters — narrow the request]"

  /** Apply [[MaxOutputChars]], naming the cut so the agent can narrow its
    * request instead of assuming it saw everything.
    */
  private def bounded(text: String): String =
    if text.length <= MaxOutputChars then text
    else text.take(MaxOutputChars) + CutMarker

  private def bounded(
      result: ToolResult[NoStructuredOutput]
  ): ToolResult[NoStructuredOutput] =
    mcp.mapText(result)(bounded)

  /** The two guarantees every result served here carries: its text stays within
    * [[MaxOutputChars]] whether the result is an error or not, and a handler
    * that throws yields a tool error rather than a transport failure the agent
    * cannot read.
    */
  private def guardedResult(
      result: => ToolResult[NoStructuredOutput]
  ): ToolResult[NoStructuredOutput] =
    try bounded(result)
    catch
      case NonFatal(e) =>
        bounded(mcp.error(Option(e.getMessage).getOrElse(e.toString)))

  /** Put a tool's logic behind [[guardedResult]]. [[start]] applies this to
    * every tool it binds, so the guard is not something a tool opts into.
    *
    * The [[NoStructuredOutput]] bound keeps a second, unbounded channel out of
    * reach: `withStructured` moves the type parameter, and `ServerTool` is
    * invariant in it, so a tool built that way no longer fits [[start]]'s list.
    */
  private[mcp] def guarded[I](
      t: ServerTool[I, NoStructuredOutput, Identity, ServerContext[Identity]]
  ): ServerTool[I, NoStructuredOutput, Identity, ServerContext[Identity]] =
    t.copy(logic =
      (in, ctx, headers) => guardedResult(t.logic(in, ctx, headers))
    )

  /** Bind `tools` on a fresh port in the enclosing scope, each [[guarded]].
    *
    * `toolTimeout` becomes the request timeout, raising it from the framework
    * default so a slow tool doesn't have its connection closed mid-call.
    */
  private[mcp] def start(
      net: Network,
      tools: List[
        ServerTool[?, NoStructuredOutput, Identity, ServerContext[Identity]]
      ],
      toolTimeout: FiniteDuration
  )(using Ox): McpHost =
    val endpoint = mcp.endpoint(tools.map(t => guarded(t)), "mcp")
    new McpHost(net.serveLocal(List(endpoint), toolTimeout))
