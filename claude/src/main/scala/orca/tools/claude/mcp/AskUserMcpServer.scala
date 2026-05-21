package orca.tools.claude.mcp

import chimp.*
import io.circe.Codec
import ox.{Ox, releaseAfterScope}
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
  * one flow don't collide. Lifecycle is tied to the surrounding Ox scope:
  * `releaseAfterScope` stops the Netty binding when the scope ends.
  *
  * Companion factory [[start]] returns the running server.
  */
private[claude] class AskUserMcpServer private (port: Int):
  /** The URL Claude Code's `.mcp.json` should target. */
  val url: String = s"http://127.0.0.1:$port/mcp"

private[claude] object AskUserMcpServer:

  /** Mount the `ask_user` MCP endpoint on a fresh Netty binding and return the
    * bound URL. The binding stops when the enclosing scope ends.
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
    releaseAfterScope(binding.stop())
    new AskUserMcpServer(binding.port)
