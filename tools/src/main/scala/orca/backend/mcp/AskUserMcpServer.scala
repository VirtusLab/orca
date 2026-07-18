package orca.backend.mcp

import chimp.*
import io.circe.Codec
import ox.Ox
import sttp.tapir.Schema
import sttp.tapir.server.netty.sync.NettySyncServer

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Input shape of the `ask_user` MCP tool: the agent fills in `question` and
  * gets the typed answer back as the tool result.
  */
private[mcp] case class AskUserInput(question: String) derives Codec, Schema

/** A tiny MCP HTTP server exposing the `ask_user` tool. The handler closes over
  * an [[AskUserBridge]] — each invocation enqueues the question and blocks
  * until the host supplies an answer.
  *
  * Bound on `127.0.0.1` at an ephemeral port so conversations don't collide.
  * Lifecycle is caller-owned: `close()` stops the Netty binding, and callers
  * should tie it to the conversation's lifetime (e.g. via
  * `Conversation.onFinalize`) so per-call bindings don't accumulate.
  */
private[orca] class AskUserMcpServer private[mcp] (
    /** The bound port. Useful when the caller wants to disambiguate per-server
      * artefacts (e.g. claude's `.orca-mcp-$port.json` config file).
      */
    val port: Int,
    stopFn: () => Unit
) extends AutoCloseable:
  /** The URL an MCP client (claude's `.mcp.json`, codex's
    * `mcp_servers.<name>.url`) should target.
    */
  val url: String = s"http://127.0.0.1:$port/mcp"

  override def close(): Unit = stopFn()

private[orca] object AskUserMcpServer:

  /** MCP server name advertised to every backend (`mcp_servers.<name>` in
    * codex's config, the `mcpServers` map key in claude's `.mcp.json`). All
    * backends must use the same name so a single MCP host binding serves them.
    */
  private[orca] val ServerName: String = "orca"

  /** MCP tool slug as advertised over the protocol. Claude qualifies this with
    * the server name from `.mcp.json` (`mcp__<server>__$ToolSlug`); codex
    * surfaces it as the bare slug with the server name in a parallel field.
    * Single source of truth — a rename ripples to every routing site.
    */
  private[orca] val ToolSlug: String = "ask_user"

  /** Upper bound on one `ask_user` invocation, from the agent's MCP request to
    * the user's answer. Both backend MCP clients and this server's Netty
    * binding must agree on a value larger than any reasonable user delay —
    * otherwise the client times out, the agent synthesises a tool failure, and
    * a follow-up `ask_user` fires while the user is still typing. Each consumer
    * converts to its native unit (claude JSON ms, codex TOML sec, gemini
    * settings.json ms, Netty `FiniteDuration`); keep in sync.
    */
  private[orca] val ToolTimeout: FiniteDuration = 1.hour

  /** Mount the `ask_user` MCP endpoint on a fresh Netty binding in the
    * enclosing Ox scope; the caller calls `close()` (or relies on scope
    * tear-down).
    *
    * The handler blocks until the host user types an answer, which can take
    * arbitrarily long, so Netty's default request/idle timeouts are raised to
    * [[ToolTimeout]] to avoid closing the connection mid-prompt. `idleTimeout`
    * adds a minute of slop because Netty requires `idleTimeout >
    * requestTimeout`.
    */
  def start(bridge: AskUserBridge)(using Ox): AskUserMcpServer =
    val askUserTool =
      tool(AskUserMcpServer.ToolSlug)
        .description(
          "Ask the host user a clarifying question and receive their " +
            "typed answer."
        )
        .input[AskUserInput]
        .handle(in => Right(bridge.ask(in.question)))
    val endpoint = mcpEndpoint(List(askUserTool), List("mcp"))
    val binding = NettySyncServer()
      .port(0)
      .modifyConfig(
        _.requestTimeout(ToolTimeout).idleTimeout(ToolTimeout + 1.minute)
      )
      .addEndpoint(endpoint)
      .start()
    new AskUserMcpServer(binding.port, () => binding.stop())

  /** Short system-prompt hint telling the agent it has an `ask_user` tool for
    * clarifying questions. Worded conservatively — agents over-use tools
    * they're told about.
    */
  val Hint: String =
    """When you genuinely need a piece of information from the user to
      |proceed (and only then — don't ask for permission to do work, don't
      |ask trivial confirmation questions), call the `ask_user` tool with a
      |single short question. The tool blocks until the user types an
      |answer; the answer comes back as the tool result, which you should
      |use to continue your work. Prefer making reasonable assumptions over
      |asking — only reach for `ask_user` when an assumption could send you
      |meaningfully wrong.""".stripMargin
