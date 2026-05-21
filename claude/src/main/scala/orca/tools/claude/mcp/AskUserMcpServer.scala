package orca.tools.claude.mcp

import chimp.*
import io.circe.Codec
import ox.Ox
import sttp.tapir.Schema
import sttp.tapir.server.netty.sync.NettySyncServer

/** Input shape of the `ask_user` MCP tool. The agent fills in `question`; we
  * hand the typed answer back as the tool result.
  */
private[claude] case class AskUserInput(question: String) derives Codec, Schema

/** Boots a tiny MCP HTTP server exposing the `ask_user` tool. The handler
  * closes over an [[AskUserBridge]] — each tool invocation enqueues the
  * question on the bridge and blocks until the host process supplies an answer.
  *
  * Bound on `127.0.0.1` at an ephemeral port so multiple conversations inside
  * one flow don't collide. Lifecycle is **caller-owned**: the factory returns
  * an `AutoCloseable` whose `close()` stops the Netty binding. Callers should
  * tie that close to the lifetime of the conversation it serves (e.g. via
  * `Conversation.onFinalize`) so per-call bindings don't accumulate over a long
  * flow.
  */
private[claude] class AskUserMcpServer private[mcp] (
    port: Int,
    stopFn: () => Unit
) extends AutoCloseable:
  /** The URL Claude Code's `.mcp.json` should target. */
  val url: String = s"http://127.0.0.1:$port/mcp"

  /** The bound port; useful when the caller wants to disambiguate per-server
    * filenames (e.g. `.orca-mcp-$port.json`).
    */
  def boundPort: Int = port

  override def close(): Unit = stopFn()

private[claude] object AskUserMcpServer:

  /** Mount the `ask_user` MCP endpoint on a fresh Netty binding. The Ox
    * capability is used to start the server in the enclosing scope; the caller
    * is responsible for calling `close()` (or relying on scope tear-down) to
    * stop it.
    */
  def start(bridge: AskUserBridge)(using Ox): AskUserMcpServer =
    val askUserTool =
      tool("ask_user")
        .description(
          "Ask the host user a clarifying question and receive their " +
            "typed answer."
        )
        .input[AskUserInput]
        .handle(in => Right(bridge.ask(in.question)))
    val endpoint = mcpEndpoint(List(askUserTool), List("mcp"))
    val binding = NettySyncServer().port(0).addEndpoint(endpoint).start()
    new AskUserMcpServer(binding.port, () => binding.stop())
