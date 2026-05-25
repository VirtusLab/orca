package orca.tools.claude

import orca.events.OrcaListener
import orca.llm.{BackendTag, LlmConfig, SessionId}
import orca.{OrcaFlowException}
import orca.backend.{Conversation, Conversations, LlmBackend, LlmResult}
import orca.subprocess.CliRunner
import orca.tools.claude.mcp.{AskUserBridge, AskUserMcpServer}
import orca.tools.claude.streamjson.OutboundMessage
import ox.Ox
import ox.channels.BufferCapacity

/** Claude Code backend. All calls — autonomous and interactive — drive a
  * stream-json subprocess through [[ClaudeConversation]]; the difference is
  * just the `canAskUser` knob on [[openConversation]]. The prompt is injected
  * as the first user turn on stdin, the subprocess emits typed NDJSON
  * responses, the driver translates them into `ConversationEvent`s.
  *
  * The autonomous path drains those events via
  * [[orca.backend.Conversations.drainAutonomous]] and returns the awaited
  * `LlmResult`. The interactive path hands the `Conversation` back to the
  * caller who runs `Interaction.drive`.
  *
  * Interactive calls also stand up an MCP host bridge: a tiny HTTP server (via
  * [[AskUserMcpServer]]) exposes an `ask_user` tool the agent can call to
  * surface a free-form clarifying question. The server's lifetime tracks the
  * conversation (via `ClaudeConversation.onFinalize`), not the backend, so a
  * long flow with many interactive calls doesn't leak Netty bindings.
  * Autonomous calls skip the bridge entirely.
  */
class ClaudeBackend(cli: CliRunner)(using Ox, BufferCapacity)
    extends LlmBackend[BackendTag.ClaudeCode.type]:

  def runHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop
  ): LlmResult[BackendTag.ClaudeCode.type] =
    invokeHeadless(prompt, config, workDir, resume = None, events)

  def continueHeadless(
      sessionId: SessionId[BackendTag.ClaudeCode.type],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop
  ): LlmResult[BackendTag.ClaudeCode.type] =
    invokeHeadless(prompt, config, workDir, resume = Some(sessionId), events)

  def runInteractive(
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.ClaudeCode.type] =
    openConversation(
      prompt,
      displayPrompt,
      config,
      workDir,
      resume = None,
      outputSchema,
      canAskUser = true
    )

  def continueInteractive(
      sessionId: SessionId[BackendTag.ClaudeCode.type],
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.ClaudeCode.type] =
    openConversation(
      prompt,
      displayPrompt,
      config,
      workDir,
      resume = Some(sessionId),
      outputSchema,
      canAskUser = true
    )

  /** Spawn `claude` in stream-json mode, write the opening user turn, close
    * stdin, and wrap the process in a live [[ClaudeConversation]]. Used by both
    * the interactive path (`runInteractive`, `canAskUser = true`) and the
    * autonomous path (`runHeadless`, `canAskUser = false`).
    *
    * The initial user turn is the only thing we feed through stdin; once it's
    * written we close the pipe so `claude --print --input-format stream-json`
    * stops waiting for EOF and starts producing output.
    *
    * `canAskUser = true` wires the MCP `ask_user` tool: we stand up an
    * [[AskUserMcpServer]] on an ephemeral port, write a workDir-local
    * `.orca-mcp-<port>.json` pointing at it, tell claude about it via
    * `--mcp-config`, and add the tool name to the auto-approve set so the user
    * isn't prompted to authorise it. When the agent calls `ask_user` the bridge
    * wakes up, the conversation emits a `UserQuestion` event, the renderer
    * prompts, and the typed answer becomes the tool result.
    *
    * `canAskUser = false` skips all of that — no MCP server, no config file, no
    * system-prompt hint, no auto-approve entry. Autonomous calls have no
    * renderer to drive the prompt, so exposing the tool would just give the
    * agent a way to deadlock the call.
    *
    * The MCP server (when present) is handed to ClaudeConversation as a session
    * resource; its `close()` runs from `onFinalize` after the read loop drains,
    * so the binding releases when the conversation ends rather than when the
    * outer flow scope tears down.
    *
    * If anything between resource allocation and conversation construction
    * throws we tear down the server (and SIGINT the process if we'd already
    * spawned it) so no Netty binding or subprocess leaks.
    */
  private def openConversation(
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      resume: Option[SessionId[BackendTag.ClaudeCode.type]],
      outputSchema: Option[String],
      canAskUser: Boolean
  ): Conversation[BackendTag.ClaudeCode.type] =
    // Allocate ask_user resources up front so we can close them deterministically
    // on a downstream failure. `None` when canAskUser=false — autonomous calls
    // don't expose the tool.
    val askUser: Option[(AskUserBridge, AskUserMcpServer, os.Path)] =
      if canAskUser then
        val bridge = new AskUserBridge
        val server = AskUserMcpServer.start(bridge)
        try
          val configFile = writeMcpConfig(server.url, server.port, workDir)
          Some((bridge, server, configFile))
        catch
          case e: Throwable =>
            server.close()
            throw e
      else None
    try
      val systemPromptFile =
        writeSystemPromptIfPresent(
          config,
          workDir,
          includeAskUserHint = canAskUser
        )
      val effectiveConfig =
        if canAskUser then config.autoApproveAlso(ClaudeBackend.AskUserToolName)
        else config
      val args = ClaudeArgs.streamJson(
        effectiveConfig,
        systemPromptFile,
        resume,
        outputSchema,
        mcpConfig = askUser.map((_, _, file) => file)
      )
      val process = cli.spawnPiped(args, cwd = workDir)
      try
        process.writeLine(
          OutboundMessage.toJson(OutboundMessage.UserText(prompt))
        )
        process.closeStdin()
        val resources = askUser.toList.flatMap: (_, server, configFile) =>
          // Order: stop the MCP server first (closes Netty workers), then
          // remove the workDir config file. Listed in close order by
          // ClaudeConversation.onFinalize.
          List(server, ClaudeBackend.deleteFileResource(configFile))
        new ClaudeConversation(
          process,
          config,
          initialPrompt = displayPrompt,
          outputSchema = outputSchema,
          askUserBridge = askUser.map((bridge, _, _) => bridge),
          sessionResources = resources
        )
      catch
        case e: Exception =>
          process.sendSigInt()
          askUser.foreach: (_, server, configFile) =>
            server.close()
            os.remove(configFile)
          throw OrcaFlowException(
            s"Failed to open claude stream-json session: ${e.getMessage}"
          )
    catch
      case e: Throwable =>
        // Pre-process-spawn failure (system-prompt write / args build):
        // tear down anything we already allocated.
        askUser.foreach: (_, server, configFile) =>
          server.close()
          if os.exists(configFile) then os.remove(configFile)
        throw e

  /** Write a workDir-local MCP config file advertising the host's MCP server.
    * Named with the server's bound port so two interactive conversations
    * sharing a `workDir` don't overwrite each other's config.
    */
  private def writeMcpConfig(
      url: String,
      port: Int,
      workDir: os.Path
  ): os.Path =
    val path = workDir / s".orca-mcp-$port.json"
    os.write.over(
      path,
      s"""{"mcpServers":{"${ClaudeBackend.McpServerName}":{"type":"http","url":"$url"}}}"""
    )
    path

  /** Autonomous invocation: opens the same stream-json [[ClaudeConversation]]
    * the interactive path uses (no MCP `ask_user`, no system-prompt hint), then
    * drains it via [[Conversations.drainAutonomous]] for the result. Retries
    * for transient failures live one layer up in `DefaultLlmCall` — it wraps
    * every structured call in `retry(effective.retrySchedule)`.
    */
  private def invokeHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      resume: Option[SessionId[BackendTag.ClaudeCode.type]],
      events: OrcaListener
  ): LlmResult[BackendTag.ClaudeCode.type] =
    val conv = openConversation(
      prompt = prompt,
      // No renderer on the autonomous path; the prompt is only ever shown
      // to the agent, never echoed back to the user.
      displayPrompt = "",
      config = config,
      workDir = workDir,
      resume = resume,
      outputSchema = None,
      canAskUser = false
    )
    try Conversations.drainAutonomous(conv, events)
    catch
      case e: OrcaFlowException =>
        throw OrcaFlowException(s"claude CLI failed: ${e.getMessage}")

  /** Build the per-session system-prompt file. Optionally includes a short note
    * about the `ask_user` MCP tool — only the interactive path passes
    * `includeAskUserHint = true`, so headless calls don't waste tokens on a
    * tool they have no MCP server for.
    */
  private def writeSystemPromptIfPresent(
      config: LlmConfig,
      workDir: os.Path,
      includeAskUserHint: Boolean = false
  ): Option[os.Path] =
    val body = (config.systemPrompt, includeAskUserHint) match
      case (Some(s), true)  => Some(s + "\n\n" + ClaudeBackend.AskUserHint)
      case (None, true)     => Some(ClaudeBackend.AskUserHint)
      case (Some(s), false) => Some(s)
      case (None, false)    => None
    body.map: text =>
      val file = workDir / ".claude" / "orca-system-prompt.md"
      os.write.over(file, text, createFolders = true)
      file

object ClaudeBackend:

  /** Returns an `AutoCloseable` that best-effort deletes the given file when
    * closed. Used as a `sessionResources` entry so each conversation's
    * `.orca-mcp-<port>.json` is removed when the conversation ends — without
    * this, long flows would accumulate orphan config files in `workDir`.
    */
  private[claude] def deleteFileResource(path: os.Path): AutoCloseable =
    () => if os.exists(path) then os.remove(path)

  /** MCP server name as it appears in `.mcp.json`. Combined with the tool name,
    * the agent sees `mcp__orca__ask_user`.
    */
  private[claude] val McpServerName: String = "orca"

  /** Fully-qualified tool name the agent uses, derived from the MCP server name
    * + the tool's bare slug. Always auto-approved on the interactive path — the
    * user is already typing an answer, no need for a y/n prompt first.
    */
  private[claude] val AskUserToolName: String =
    s"mcp__${McpServerName}__${mcp.AskUserMcpServer.ToolSlug}"

  /** Short hint appended to the system prompt on the interactive path, telling
    * the agent it has an `ask_user` tool for clarifying questions. Worded
    * conservatively — agents over-use tools they're told about.
    */
  private[claude] val AskUserHint: String =
    """When you genuinely need a piece of information from the user to
      |proceed (and only then — don't ask for permission to do work, don't
      |ask trivial confirmation questions), call the `ask_user` tool with a
      |single short question. The tool blocks until the user types an
      |answer; the answer comes back as the tool result, which you should
      |use to continue your work. Prefer making reasonable assumptions over
      |asking — only reach for `ask_user` when an assumption could send you
      |meaningfully wrong.""".stripMargin
