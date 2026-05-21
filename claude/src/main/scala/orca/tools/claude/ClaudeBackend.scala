package orca.tools.claude

import orca.llm.{BackendTag, LlmConfig, SessionId}
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.{OrcaFlowException}
import orca.backend.{Conversation, LlmBackend, LlmResult}
import orca.subprocess.CliRunner
import orca.tools.claude.mcp.{AskUserBridge, AskUserMcpServer}
import orca.tools.claude.streamjson.OutboundMessage
import ox.Ox
import ox.channels.BufferCapacity

/** Claude Code backend. Headless calls go through `claude -p --output-format
  * json` — single-shot, parses the JSON result. Interactive calls drive a
  * stream-json subprocess through [[ClaudeConversation]]: the prompt is
  * injected as the first user turn on stdin, the subprocess emits typed NDJSON
  * responses, the driver translates them into `ConversationEvent`s the channel
  * renders.
  *
  * Interactive calls also stand up an MCP host bridge: a tiny HTTP server (via
  * [[AskUserMcpServer]]) exposes an `ask_user` tool the agent can call to
  * surface a free-form clarifying question. The server's lifetime tracks the
  * conversation (via `ClaudeConversation.onFinalize`), not the backend, so a
  * long flow with many interactive calls doesn't leak Netty bindings.
  */
class ClaudeBackend(cli: CliRunner)(using Ox, BufferCapacity)
    extends LlmBackend[BackendTag.ClaudeCode.type]:

  def runHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[BackendTag.ClaudeCode.type] =
    invokeHeadless(prompt, config, workDir, resume = None)

  def continueHeadless(
      sessionId: SessionId[BackendTag.ClaudeCode.type],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[BackendTag.ClaudeCode.type] =
    invokeHeadless(prompt, config, workDir, resume = Some(sessionId))

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
      outputSchema
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
      outputSchema
    )

  /** Spawn `claude` in stream-json mode, write the opening user turn, close
    * stdin, and wrap the process in a live [[ClaudeConversation]].
    *
    * The initial user turn is the only one we feed through stdin; once it's
    * written we close the pipe so `claude --print --input-format stream-json`
    * stops waiting for EOF and starts producing output. Mid-session user input
    * (clarifying questions) flows through the MCP host bridge instead: we stand
    * up an [[AskUserMcpServer]] on an ephemeral port, write a workDir-local
    * `.orca-mcp-<port>.json` pointing at it, and tell claude about it via
    * `--mcp-config`. When the agent calls the `ask_user` tool the bridge wakes
    * up, the conversation emits a `UserQuestion` event, the renderer prompts,
    * and the typed answer becomes the tool result.
    *
    * The MCP server is handed to ClaudeConversation as a session resource; its
    * `close()` runs from `onFinalize` after the conversation's read loop
    * drains, so the binding releases when the conversation ends rather than
    * when the outer flow scope tears down.
    *
    * If anything between server start and conversation construction throws we
    * stop the server (and SIGINT the process if we'd already spawned it) so no
    * Netty binding or subprocess leaks.
    */
  private def openConversation(
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      resume: Option[SessionId[BackendTag.ClaudeCode.type]],
      outputSchema: Option[String]
  ): Conversation[BackendTag.ClaudeCode.type] =
    val bridge = new AskUserBridge
    val mcpServer = AskUserMcpServer.start(bridge)
    try
      val mcpConfigFile =
        writeMcpConfig(mcpServer.url, mcpServer.boundPort, workDir)
      val systemPromptFile =
        writeSystemPromptIfPresent(config, workDir, includeAskUserHint = true)
      val effectiveConfig =
        config.withAlsoAllowedTool(ClaudeBackend.AskUserToolName)
      val args = ClaudeArgs.streamJson(
        effectiveConfig,
        systemPromptFile,
        resume,
        outputSchema,
        mcpConfig = Some(mcpConfigFile)
      )
      val process = cli.spawnPiped(args, cwd = workDir)
      try
        process.writeLine(
          OutboundMessage.toJson(OutboundMessage.UserText(prompt))
        )
        process.closeStdin()
        new ClaudeConversation(
          process,
          config,
          initialPrompt = displayPrompt,
          outputSchema = outputSchema,
          askUserBridge = Some(bridge),
          sessionResources = List(mcpServer)
        )
      catch
        case e: Exception =>
          process.sendSigInt()
          mcpServer.close()
          throw OrcaFlowException(
            s"Failed to open claude stream-json session: ${e.getMessage}"
          )
    catch
      case e: Throwable =>
        // Pre-process-spawn failure (config write / args build): stop the
        // server we already started so the Netty binding doesn't leak.
        mcpServer.close()
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

  private def invokeHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      resume: Option[SessionId[BackendTag.ClaudeCode.type]]
  ): LlmResult[BackendTag.ClaudeCode.type] =
    val systemPromptFile = writeSystemPromptIfPresent(config, workDir)
    val args = ClaudeArgs.headless(prompt, config, systemPromptFile, resume)
    // Retries for transient failures live one layer up in DefaultLlmCall —
    // it wraps every structured call in `retry(effective.retrySchedule)`.
    // Plain ask/startSession/continueSession callers skip that loop by
    // design (raw-text path; the caller decides the retry policy).
    val result = cli.run(args, cwd = workDir)
    if result.exitCode != 0 then
      val diagnostic =
        if result.stderr.nonEmpty then result.stderr else result.stdout
      throw OrcaFlowException(
        s"claude CLI failed (exit ${result.exitCode}): $diagnostic"
      )
    val response = readFromString[ClaudeHeadlessResponse](result.stdout)
    if response.is_error.contains(true) then
      throw OrcaFlowException(s"claude reported an error: ${response.result}")
    response.toLlmResult

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

  /** MCP server name as it appears in `.mcp.json`. Combined with the tool name,
    * the agent sees `mcp__orca__ask_user`.
    */
  private[claude] val McpServerName: String = "orca"

  /** Fully-qualified tool name the agent uses, derived from the MCP server name
    * + tool name. Always auto-approved on the interactive path — the user is
    * already typing an answer, no need for a y/n prompt first.
    */
  private[claude] val AskUserToolName: String =
    s"mcp__${McpServerName}__ask_user"

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
