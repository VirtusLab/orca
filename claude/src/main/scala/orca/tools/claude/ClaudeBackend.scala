package orca.tools.claude

import java.util.concurrent.atomic.AtomicBoolean

import orca.events.OrcaListener
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  SessionId,
  StructuredOutputMode,
  ToolSet,
  onWire
}
import orca.backend.{
  Conversation,
  Conversations,
  AgentBackend,
  AgentResult,
  ConversationMode,
  IdScheme,
  SessionSupport,
  SubprocessSpawn,
  SystemPromptComposer
}
import orca.subprocess.CliRunner
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.tools.claude.streamjson.OutboundMessage
import ox.Ox

/** Claude Code backend. All calls — autonomous and interactive — drive a
  * stream-json subprocess through [[ClaudeConversation]]; the only difference
  * is the [[ConversationMode]] passed to `openConversation` (autonomous omits
  * the ask_user MCP, interactive wires it). The autonomous path drains events
  * and returns the awaited `AgentResult`; the interactive path hands the
  * `Conversation` back to the caller who runs `Interaction.drive`.
  *
  * Interactive calls also stand up an MCP host bridge: a tiny HTTP server (via
  * [[AskUserMcpServer]]) exposing an `ask_user` tool. Its lifetime tracks the
  * conversation (via `ClaudeConversation.onFinalize`), not the backend, so a
  * long flow with many interactive calls doesn't leak Netty bindings.
  */
private[orca] class ClaudeBackend(
    cli: CliRunner,
    networkTools: Seq[String] = ClaudeBackend.DefaultNetworkTools,
    private[claude] val projectsDir: os.Path = os.home / ".claude" / "projects",
    /** Shared between the spawn path ([[openConversation]]) and the existence
      * probe (see [[sessions]]): claude writes a session's transcript under
      * `<projectsDir>/<cwdSlug(workDir)>/<id>.jsonl`, so both sides reading the
      * SAME field keeps the probe honest. The `os.pwd` default serves only
      * bare/test construction; the runtime passes the flow's real `workDir`.
      */
    override val workDir: os.Path = os.pwd,
    /** Threaded into [[AgentBackend]]'s `closedFlag`. Bare construction gets a
      * fresh flag; [[withNetworkTools]] passes THIS instance's flag so the
      * sibling shares one latch with its parent — see `AgentBackend` for why.
      */
    sharedClosedFlag: AtomicBoolean = new AtomicBoolean(false)
) extends AgentBackend[BackendTag.ClaudeCode.type](sharedClosedFlag):

  /** Return a sibling backend that, on [[ToolSet.NetworkOnly]] turns,
    * pre-approves `tools` (claude `--allowedTools` syntax). Lives on the
    * backend, not `AgentConfig`, since the strings are claude-specific.
    *
    * Shares `closedFlag` with `this`: the sibling is a genuinely different
    * `AgentBackend` instance, so without threading the SAME flag through, a
    * handle derived here and leaked past flow-end would bypass the
    * use-after-close guard.
    */
  def withNetworkTools(tools: Seq[String]): ClaudeBackend =
    new ClaudeBackend(cli, tools, projectsDir, workDir, closedFlag)

  /** Claude's sessions live on disk (`~/.claude/projects/.../<id>.jsonl`) and
    * outlive the process, so it is durable: the claim survives a restart
    * (rehydrated on resume so a resumed task uses `--resume`), and existence is
    * a best-effort transcript-file probe. Because
    * [[SessionSupport.willContinue]] resolves the recorded mapping first, the
    * probe only runs for an id already known: a stray transcript for a
    * never-claimed id reports `false`, which is safe since the caller re-seeds.
    */
  val tag: BackendTag.ClaudeCode.type = BackendTag.ClaudeCode

  override def enforcement(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): Enforcement =
    ClaudeArgs.enforcement(tools, autoApprove)

  /** `--json-schema` (passed whenever a structured call supplies a schema — see
    * [[runAutonomous]]) makes the CLI inject a StructuredOutput tool whose
    * parameters are the schema's top-level properties; the payload arrives as
    * that tool call, never as reply text.
    */
  override def structuredOutputMode: StructuredOutputMode =
    StructuredOutputMode.Tool

  /** The sole session handle. [[IdScheme.ClientClaimed]]: ids are claimed via
    * `--session-id` so subsequent calls use `--resume` (the CLI refuses to
    * reuse `--session-id` once the session exists).
    */
  val sessions: SessionSupport[BackendTag.ClaudeCode.type] =
    SessionSupport.durable(
      IdScheme.ClientClaimed,
      id =>
        os.exists(
          projectsDir / ClaudeBackend.cwdSlug(workDir) / s"$id.jsonl"
        )
    )

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.ClaudeCode.type],
      config: AgentConfig,
      events: OrcaListener,
      outputSchema: Option[String]
  ): AgentResult[BackendTag.ClaudeCode.type] =
    // Commit happens only after a successful drain: a subprocess that crashed
    // before claude registered the session id would otherwise leave the registry
    // wedged. The ordering matters for the NEXT `session(...)` call, which must
    // see a registry that agrees with what claude actually did.
    Conversations.runAutonomous(session, sessions, events):
      openConversation(
        prompt = prompt,
        mode = ConversationMode.Autonomous,
        session = session,
        config = config,
        outputSchema = outputSchema
      )

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.ClaudeCode.type],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.ClaudeCode.type] =
    val conv = openConversation(
      prompt = prompt,
      mode = ConversationMode.Interactive(displayPrompt),
      session = session,
      config = config,
      outputSchema = outputSchema
    )
    // Interactive has no in-backend drain to gate on; commit once the
    // conversation is up. A crash mid-conversation leaves the mark in place, but
    // interactive sessions aren't auto-retried — the user reruns with a fresh
    // session. claude claims ids client-side, so the client id IS the wire id
    // (`onWire`), which is always safe to `register`.
    sessions.register(session, session.onWire)
    conv

  /** Spawn `claude` in stream-json mode, write the opening user turn, close
    * stdin, and wrap the process in a live [[ClaudeConversation]]. Closing
    * stdin after the initial turn is what makes `claude --print` stop waiting
    * for EOF and start producing output.
    *
    * `Interactive` mode wires the MCP `ask_user` tool: stand up an
    * [[AskUserMcpServer]] on an ephemeral port, write a workDir-local
    * `.orca-mcp-<port>.json`, point claude at it via `--mcp-config`, and
    * auto-approve the tool. `Autonomous` skips all of that: those calls have no
    * renderer to drive the prompt, so exposing the tool would let the agent
    * deadlock the call.
    *
    * The MCP server (when present) is a session resource closed from
    * `onFinalize` after the read loop drains. If anything between resource
    * allocation and conversation construction throws we tear down the server
    * (and SIGINT the process if already spawned) so nothing leaks.
    */
  private def openConversation(
      prompt: String,
      mode: ConversationMode,
      session: SessionId[BackendTag.ClaudeCode.type],
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.ClaudeCode.type] =
    // Allocate ask_user resources up front so a downstream failure can close
    // them deterministically. `None` for autonomous — those calls don't expose
    // the tool.
    val displayPrompt = mode.displayPrompt
    val askUser: Option[AskUserSession] =
      Option.when(mode.isInteractive):
        AskUserSession.allocate: server =>
          writeMcpConfig(server, workDir)
          List(
            SubprocessSpawn.deleteFileResource(mcpConfigPath(server, workDir))
          )
    SubprocessSpawn.open("claude stream-json", askUser.toList) {
      val systemPromptFile =
        writeSystemPromptIfPresent(
          config,
          includeAskUserHint = askUser.isDefined
        )
      val effectiveConfig =
        if askUser.isDefined then
          config.autoApproveAlso(ClaudeBackend.AskUserToolName)
        else config
      // The registry decides fresh-vs-resume. Callers must not share a session
      // id across concurrent calls; `reviewAndFixLoop`'s parallel reviewer
      // fan-out is safe because each reviewer mints its own conversation via
      // `agent.chat()`.
      val args = ClaudeArgs.streamJson(
        effectiveConfig,
        systemPromptFile,
        dispatch = sessions.dispatchFor(session),
        outputSchema,
        mcpConfig = askUser.map(r => mcpConfigPath(r.server, workDir)),
        networkTools = networkTools
      )
      cli.spawnPiped(args, cwd = workDir)
    } { process =>
      process.writeLine(
        OutboundMessage.toJson(OutboundMessage.UserText(prompt))
      )
      process.closeStdin()
      new ClaudeConversation(
        process,
        config,
        initialPrompt = displayPrompt,
        outputSchema = outputSchema,
        askUser = askUser
      )
    }

  /** Path of the workDir-local MCP config file advertising the host's MCP
    * server. Named with the bound port so two interactive conversations sharing
    * a `workDir` don't overwrite each other's config.
    */
  private def mcpConfigPath(
      server: AskUserMcpServer,
      workDir: os.Path
  ): os.Path = workDir / s".orca-mcp-${server.port}.json"

  /** Write the MCP config file at [[mcpConfigPath]].
    *
    * The `timeout` field extends claude's per-server tool-call timeout from its
    * 60s default to [[AskUserMcpServer.ToolTimeout]]. Without it, claude gives
    * up on `ask_user` if the human takes more than 60s to answer, then fires a
    * follow-up `ask_user` and the user answers twice.
    *
    * One of three renderings of `AskUserMcpServer.ToolTimeout` (claude JSON ms
    * / codex TOML sec / gemini settings.json ms); keep in sync.
    */
  private def writeMcpConfig(
      server: AskUserMcpServer,
      workDir: os.Path
  ): Unit =
    val timeoutMs = AskUserMcpServer.ToolTimeout.toMillis
    os.write.over(
      mcpConfigPath(server, workDir),
      s"""{"mcpServers":{"${AskUserMcpServer.ServerName}":{"type":"http","url":"${server.url}","timeout":$timeoutMs}}}"""
    )

  /** Build the per-session system-prompt file: compose `config.systemPrompt`
    * with the ask_user hint (interactive only), then write to a JVM temp file
    * (auto-cleaned on exit) rather than the user's workDir — it's purely an IPC
    * mechanism, read once via `--append-system-prompt-file`.
    */
  private def writeSystemPromptIfPresent(
      config: AgentConfig,
      includeAskUserHint: Boolean
  ): Option[os.Path] =
    val hint = Option.when(includeAskUserHint)(AskUserMcpServer.Hint)
    SystemPromptComposer
      .combine(config, hint)
      .map: text =>
        os.temp(prefix = "orca-system-prompt-", suffix = ".md", contents = text)

object ClaudeBackend:

  /** Derives the project-directory slug that claude uses under
    * `~/.claude/projects/`: replaces every `/` in the absolute path with `-`.
    * E.g. `/home/foo/bar` → `-home-foo-bar`.
    */
  private[claude] def cwdSlug(cwd: os.Path): String =
    cwd.toString.replace('/', '-')

  /** Read-only network tools pre-approved on [[ToolSet.NetworkOnly]] turns.
    * Command-scoped, so plan mode still blocks general bash and all edits.
    * `Bash(gh api:*)` is broad GitHub reads — note `gh api -X POST` can mutate
    * GitHub (not local files); flows wanting a tighter set pass their own via
    * `claude.withNetworkTools(...)`.
    */
  private[claude] val DefaultNetworkTools: Seq[String] = Seq(
    "WebFetch",
    "WebSearch",
    "Bash(gh issue view:*)",
    "Bash(gh pr view:*)",
    "Bash(gh search:*)",
    "Bash(gh repo view:*)",
    "Bash(gh api:*)"
  )

  /** Fully-qualified tool name (MCP server name + tool slug). Always
    * auto-approved on the interactive path — the user is already typing an
    * answer, no need for a y/n prompt first.
    */
  private[claude] val AskUserToolName: String =
    s"mcp__${AskUserMcpServer.ServerName}__${AskUserMcpServer.ToolSlug}"

  /** Tool name the claude CLI injects for `--json-schema` structured output:
    * the model "exits" the turn by calling this tool with the payload as its
    * input. The conversation suppresses that echo — the payload reaches the
    * caller via the result message as `OrcaEvent.StructuredResult`, so
    * rendering the tool call too would show the same JSON twice.
    */
  private[claude] val StructuredOutputToolName: String = "StructuredOutput"
