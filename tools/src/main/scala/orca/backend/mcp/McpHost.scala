package orca.backend.mcp

import chimp.{ServerTool, mcpEndpoint}
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

  /** Chars any one tool result returns, on either channel. Past this the tail
    * is dropped and the result says so, so a single call costs a bounded number
    * of tokens rather than the turn's whole context.
    */
  private[mcp] val MaxOutputChars: Int = 60000

  /** Apply [[MaxOutputChars]], naming the cut so the agent can narrow its
    * request instead of assuming it saw everything.
    */
  private def bounded(output: String): String =
    if output.length <= MaxOutputChars then output
    else
      output.take(MaxOutputChars) +
        s"\n\n[cut after $MaxOutputChars characters — narrow the request]"

  /** The two guarantees every result served here carries: neither channel
    * exceeds [[MaxOutputChars]], and a handler that throws yields a tool error
    * rather than a transport failure the agent cannot read.
    */
  private def guardedResult(
      result: => Either[String, String]
  ): Either[String, String] =
    try result.map(bounded).left.map(bounded)
    catch
      case NonFatal(e) =>
        Left(bounded(Option(e.getMessage).getOrElse(e.toString)))

  /** Put a tool's logic behind [[guardedResult]]. [[start]] applies this to
    * every tool it binds, which is what makes the guarantees structural: a tool
    * cannot opt out of them by forgetting.
    */
  private[mcp] def guarded[I](
      t: ServerTool[I, Identity]
  ): ServerTool[I, Identity] =
    t.copy(logic = (in, headers) => guardedResult(t.logic(in, headers)))

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
      tools: List[ServerTool[?, Identity]],
      toolTimeout: FiniteDuration
  )(using Ox): McpHost =
    val binding = NettySyncServer()
      .port(0)
      .modifyConfig(
        _.requestTimeout(toolTimeout).idleTimeout(toolTimeout + 1.minute)
      )
      .addEndpoint(mcpEndpoint(tools.map(t => guarded(t)), List("mcp")))
      .start()
    val stopped = new java.util.concurrent.atomic.AtomicBoolean(false)
    useCloseableInScope(
      new McpHost(
        binding.port,
        () => if stopped.compareAndSet(false, true) then binding.stop()
      )
    )
