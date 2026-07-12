package orca.tools.claude

import java.util.concurrent.atomic.AtomicBoolean

import orca.events.OrcaListener
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  SessionId,
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
  * the ask_user MCP, interactive wires it). The prompt is injected as the first
  * user turn on stdin, the subprocess emits typed NDJSON responses, the driver
  * translates them into `ConversationEvent`s.
  *
  * The autonomous path drains those events via
  * [[orca.backend.Conversations.drainAutonomous]] and returns the awaited
  * `AgentResult`. The interactive path hands the `Conversation` back to the
  * caller who runs `Interaction.drive`.
  *
  * Interactive calls also stand up an MCP host bridge: a tiny HTTP server (via
  * [[AskUserMcpServer]]) exposes an `ask_user` tool the agent can call to
  * surface a free-form clarifying question. The server's lifetime tracks the
  * conversation (via `ClaudeConversation.onFinalize`), not the backend, so a
  * long flow with many interactive calls doesn't leak Netty bindings.
  * Autonomous calls skip the bridge entirely.
  */
private[orca] class ClaudeBackend(
    cli: CliRunner,
    networkTools: Seq[String] = ClaudeBackend.DefaultNetworkTools,
    private[claude] val projectsDir: os.Path = os.home / ".claude" / "projects",
    /** Fixed at construction and shared, by construction, between the spawn
      * path ([[openConversation]]) and the existence probe (see [[sessions]]):
      * claude writes a session's transcript under
      * `<projectsDir>/<cwdSlug(workDir)>/<id>.jsonl`, so both sides reading the
      * SAME field is what keeps the probe honest — no separate value can drift
      * out of sync with where agents actually spawn. The `os.pwd` default
      * serves only bare/test construction (`new ClaudeBackend(cli)`) where no
      * flow workDir exists; the runtime (`DefaultFlowContext.withDefaults`)
      * passes the flow's real `workDir`.
      */
    override val workDir: os.Path = os.pwd,
    /** Threaded straight into [[AgentBackend]]'s `closedFlag` parameter. Bare
      * construction gets a fresh flag; [[withNetworkTools]] passes THIS
      * instance's flag so the sibling it builds shares one latch with its
      * parent — see the parameter's scaladoc on `AgentBackend` for why a
      * backend-swapping builder must do this.
      */
    sharedClosedFlag: AtomicBoolean = new AtomicBoolean(false)
) extends AgentBackend[BackendTag.ClaudeCode.type](sharedClosedFlag):

  /** Return a sibling backend that, on [[ToolSet.NetworkOnly]] turns,
    * pre-approves `tools` (claude `--allowedTools` syntax). The configuration
    * seam behind `ClaudeAgent.withNetworkTools`; lives on the backend, not
    * `AgentConfig`, since the strings are claude-specific.
    *
    * Shares `closedFlag` with `this` rather than starting a fresh one: the
    * sibling is a genuinely different `AgentBackend` instance (not a
    * `copyTool`-style clone reusing the same backend), so without threading the
    * SAME `AtomicBoolean` through, a handle derived here and leaked past
    * flow-end would carry its own always-open latch and bypass the
    * use-after-close guard entirely.
    */
  def withNetworkTools(tools: Seq[String]): ClaudeBackend =
    new ClaudeBackend(cli, tools, projectsDir, workDir, closedFlag)

  /** Claude's sessions live on disk (`~/.claude/projects/.../<id>.jsonl`) and
    * outlive the process, so it is durable: the claim survives a restart
    * (persisted to the progress log, rehydrated on resume so a resumed task
    * uses `--resume` rather than re-creating an already-existing
    * `--session-id`), and existence is a best-effort transcript-file probe.
    *
    * The probe checks `<projectsDir>/<cwdSlug>/<id>.jsonl` — the project-dir
    * slug replaces every `/` in the working directory with `-` (e.g.
    * `/home/foo/orca` → `-home-foo-orca`). Because
    * [[SessionSupport.willContinue]] resolves the recorded mapping first, the
    * probe only runs for an id already known (claimed this run, or rehydrated
    * from the log): a stray transcript file for an id never claimed reports
    * `false` — safe, since the caller re-seeds. The write-door guard rejects
    * unsafe ids (path traversal such as `../../etc/passwd`).
    */
  val tag: BackendTag.ClaudeCode.type = BackendTag.ClaudeCode

  override def enforcement(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): Enforcement =
    ClaudeArgs.enforcement(tools, autoApprove)

  /** The sole session handle. [[IdScheme.ClientClaimed]]: ids are claimed via
    * `--session-id` so subsequent calls use `--resume` (the CLI refuses to
    * reuse `--session-id` once the session exists). The bookkeeping is
    * encapsulated, so the spawn/commit paths go through `sessions.dispatchFor`
    * / `sessions.register` / `Conversations.runAutonomous(session, sessions,
    * …)`.
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
    // drainAndCommit commits only after a successful drain: a subprocess that
    // crashed before claude could register the session id (e.g. exit before
    // `system.init`) would otherwise leave the registry wedged. That crash
    // surfaces as AgentTurnFailed and is never auto-retried — the commit
    // ordering matters for the NEXT `session(...)` call (or a resumed run),
    // which must see a registry that agrees with what claude actually did.
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
    // conversation is up (the spawn succeeded, claude has parsed args).
    // A crash mid-conversation will still leave the mark in place, but
    // interactive sessions aren't auto-retried by the orchestrator —
    // the user reruns with a fresh session. Through `register`
    // (log-and-skip guard, the correct policy for the interactive path) —
    // claude claims its ids client-side, so the client id IS the wire id
    // (`onWire`), which is always safe.
    sessions.register(session, session.onWire)
    conv

  /** Spawn `claude` in stream-json mode, write the opening user turn, close
    * stdin, and wrap the process in a live [[ClaudeConversation]]. Used by both
    * the interactive path ([[ConversationMode.Interactive]]) and the autonomous
    * path ([[ConversationMode.Autonomous]]).
    *
    * The initial user turn is the only thing we feed through stdin; once it's
    * written we close the pipe so `claude --print --input-format stream-json`
    * stops waiting for EOF and starts producing output.
    *
    * `Interactive` mode wires the MCP `ask_user` tool: we stand up an
    * [[AskUserMcpServer]] on an ephemeral port, write a workDir-local
    * `.orca-mcp-<port>.json` pointing at it, tell claude about it via
    * `--mcp-config`, and add the tool name to the auto-approve set so the user
    * isn't prompted to authorise it. The mode also carries the `displayPrompt`
    * the conversation surfaces to the renderer as an opening `UserMessage`.
    *
    * `Autonomous` mode skips all of that — no MCP server, no config file, no
    * system-prompt hint, no auto-approve entry, no opening `UserMessage`.
    * Autonomous calls have no renderer to drive the prompt, so exposing the
    * tool would just give the agent a way to deadlock the call.
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
      mode: ConversationMode,
      session: SessionId[BackendTag.ClaudeCode.type],
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.ClaudeCode.type] =
    // Allocate ask_user resources up front so we can close them
    // deterministically on a downstream failure. `None` for autonomous —
    // those calls don't expose the tool. Claude's `extras` deletes the
    // workDir-local `.orca-mcp-<port>.json` when the conversation ends.
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
      // The registry decides fresh-vs-resume; commit happens only after the
      // conversation is up (in the runAutonomous/runInteractive shells) so
      // a spawn that fails before claude registers the session doesn't
      // leave the registry wedged. This is not about enabling an automatic
      // retry (a post-spawn failure is AgentTurnFailed and is never
      // auto-retried) — it's so the NEXT `session(...)` call, whenever it
      // comes, still sees `--session-id` as the correct dispatch.
      // Callers must not share a session id across concurrent calls;
      // `reviewAndFixLoop`'s parallel reviewer fan-out is safe because each
      // reviewer mints its own distinct conversation via `agent.chat()`.
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
    * default (60s in the MCP TS SDK claude uses) to
    * [[AskUserMcpServer.ToolTimeout]]. Without this, claude's MCP client gives
    * up on `ask_user` if the human takes more than 60s to type their answer —
    * claude then synthesises a tool failure, fires a follow-up `ask_user` with
    * a similar question, and the user ends up answering twice.
    *
    * One of three renderings of `AskUserMcpServer.ToolTimeout` — claude JSON ms
    * / codex TOML sec / gemini settings.json ms; keep in sync.
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

  /** Build the per-session system-prompt file. Composes `config.systemPrompt`
    * with the shared ask_user hint (interactive only), then writes to a JVM
    * temp file (auto-cleaned on exit) rather than the user's workDir — the file
    * is purely an IPC mechanism between orca and the `claude` subprocess (read
    * once on startup via `--append-system-prompt-file`).
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

  /** Read-only network tools pre-approved on [[ToolSet.NetworkOnly]] turns, so
    * an autonomous planner can read issues/PRs/web without a permission prompt
    * it can't answer. Command-scoped, so plan mode still blocks general bash
    * and all edits. `Bash(gh api:*)` is broad GitHub reads — note `gh api -X
    * POST` can mutate GitHub (not local files); flows wanting a tighter set
    * pass their own via `claude.withNetworkTools(...)`.
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

  /** Fully-qualified tool name the agent uses, derived from the MCP server name
    * + the tool's bare slug. Always auto-approved on the interactive path — the
    * user is already typing an answer, no need for a y/n prompt first.
    */
  private[claude] val AskUserToolName: String =
    s"mcp__${AskUserMcpServer.ServerName}__${AskUserMcpServer.ToolSlug}"

  /** Tool name the claude CLI injects for `--json-schema` structured output
    * (observed on 2.1.207): the model "exits" the turn by calling this tool
    * with the payload as its input, so the wire carries a visible `tool_use`
    * for it. The conversation suppresses that echo — the payload reaches the
    * caller via the result message and surfaces as
    * `OrcaEvent.StructuredResult`, and rendering the tool call too would show
    * the same JSON twice.
    */
  private[claude] val StructuredOutputToolName: String = "StructuredOutput"
