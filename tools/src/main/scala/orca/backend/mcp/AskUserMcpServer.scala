package orca.backend.mcp

import chimp.*
import io.circe.Codec
import ox.Ox
import sttp.tapir.Schema

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Input shape of the `ask_user` MCP tool: the agent fills in `question` and
  * gets the typed answer back as the tool result.
  */
private[mcp] case class AskUserInput(question: String) derives Codec, Schema

/** The `ask_user` MCP tool: each invocation enqueues the question on an
  * [[AskUserBridge]] and blocks until the host supplies an answer.
  */
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

  /** Mount the `ask_user` tool on a fresh [[McpHost]]. The handler blocks until
    * the host user types an answer, which is why [[ToolTimeout]] is an hour.
    */
  def start(bridge: AskUserBridge)(using Ox): McpHost =
    val askUserTool =
      tool(AskUserMcpServer.ToolSlug)
        .description(
          "Ask the host user a clarifying question and receive their " +
            "typed answer."
        )
        .input[AskUserInput]
        .handle(in => Right(bridge.ask(in.question)))
    McpHost.start(List(askUserTool), ToolTimeout)

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
