package orca.backend.mcp

import chimp.protocol.ToolContent
import chimp.server.{
  McpServer,
  NoStructuredOutput,
  ServerContext,
  ServerTool,
  ToolResult
}
import ox.{Ox, useCloseableInScope}
import sttp.shared.Identity
import sttp.tapir.server.netty.sync.NettySyncServer

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

/** One MCP server orca stands up for a single agent turn: a Netty binding on
  * `127.0.0.1` at an ephemeral port, serving whatever tools it was started
  * with.
  *
  * Callers own the lifetime — tie `close()` to the conversation, not to the
  * backend, so a long flow doesn't accumulate bindings. `close()` is
  * idempotent, so a caller that also registers it as a scope resource is safe.
  */
private[orca] class McpHost private[mcp] (val port: Int, stopFn: () => Unit)
    extends AutoCloseable:

  /** The URL an MCP client (claude's `.mcp.json`, codex's
    * `mcp_servers.<name>.url`) should target.
    */
  val url: String = s"http://127.0.0.1:$port/mcp"

  override def close(): Unit = stopFn()

private[orca] object McpHost:

  /** Chars one tool result returns in total, success or error, across every
    * text block it carries. Past this the tail is dropped and the result says
    * so, so a single call costs a bounded number of tokens rather than the
    * turn's whole context.
    */
  private[mcp] val MaxOutputChars: Int = 60000

  /** Names the cut so the agent can narrow its request instead of assuming it
    * saw everything.
    */
  private val CutMarker: String =
    s"\n\n[cut after $MaxOutputChars characters — narrow the request]"

  /** Spend [[MaxOutputChars]] across the result's blocks in order: text that
    * fits passes through, the block that overruns the budget is cut and marked,
    * and what follows it is dropped. Non-text content spends no budget — it
    * carries no text to cut.
    */
  private def bounded(content: List[ToolContent]): List[ToolContent] =
    def spend(left: Int, rest: List[ToolContent]): List[ToolContent] =
      rest match
        case Nil => Nil
        case (t: ToolContent.Text) :: tail =>
          if t.text.length <= left then t :: spend(left - t.text.length, tail)
          else List(t.copy(text = t.text.take(left) + CutMarker))
        case other :: tail => other :: spend(left, tail)
    spend(MaxOutputChars, content)

  private def bounded(
      result: ToolResult[NoStructuredOutput]
  ): ToolResult[NoStructuredOutput] =
    result.copy(content = bounded(result.content))

  /** The two guarantees every result served here carries: its text stays within
    * [[MaxOutputChars]] whether the result is an error or not, and a handler
    * that throws yields a tool error rather than a transport failure the agent
    * cannot read.
    *
    * Text is the only channel to bound: [[NoStructuredOutput]] rules out a
    * structured payload, and other content carries no text.
    */
  private def guardedResult(
      result: => ToolResult[NoStructuredOutput]
  ): ToolResult[NoStructuredOutput] =
    try bounded(result)
    catch
      case NonFatal(e) =>
        bounded(ToolResult.error(Option(e.getMessage).getOrElse(e.toString)))

  /** Put a tool's logic behind [[guardedResult]]. [[start]] applies this to
    * every tool it binds, which is what makes the guarantees structural: a tool
    * cannot opt out of them by forgetting. Tools are fixed to
    * [[NoStructuredOutput]] so that holds by type — a structured payload would
    * be a second channel this guard does not see.
    */
  private[mcp] def guarded[I](
      t: ServerTool[I, NoStructuredOutput, Identity, ServerContext[Identity]]
  ): ServerTool[I, NoStructuredOutput, Identity, ServerContext[Identity]] =
    t.copy(logic =
      (in, ctx, headers) => guardedResult(t.logic(in, ctx, headers))
    )

  /** Bind `tools` on a fresh port in the enclosing scope, each [[guarded]].
    *
    * `toolTimeout` becomes Netty's request timeout, raising it from the
    * framework default so a slow tool doesn't have its connection closed
    * mid-call; `idleTimeout` adds a minute of slop because Netty requires it to
    * exceed the request timeout.
    *
    * Registered with the scope as well as returned: tapir's `start()` leaves
    * teardown to the caller, and a turn that dies before closing the host would
    * otherwise strand the binding's event-loop threads for the life of the JVM.
    */
  private[mcp] def start(
      tools: List[
        ServerTool[?, NoStructuredOutput, Identity, ServerContext[Identity]]
      ],
      toolTimeout: FiniteDuration
  )(using Ox): McpHost =
    val binding = NettySyncServer()
      .port(0)
      .modifyConfig(
        _.requestTimeout(toolTimeout).idleTimeout(toolTimeout + 1.minute)
      )
      .addEndpoint(
        McpServer(tools = tools.map(t => guarded(t))).endpoint(List("mcp"))
      )
      .start()
    val stopped = new java.util.concurrent.atomic.AtomicBoolean(false)
    useCloseableInScope(
      new McpHost(
        binding.port,
        () => if stopped.compareAndSet(false, true) then binding.stop()
      )
    )
