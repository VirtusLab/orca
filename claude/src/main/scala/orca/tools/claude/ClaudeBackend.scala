package orca.tools.claude

import orca.events.OrcaListener
import orca.agents.{BackendTag, AgentConfig, SessionId}
import orca.backend.{
  Conversation,
  Conversations,
  AgentBackend,
  AgentResult,
  SessionMode,
  SessionRegistry,
  SubprocessSpawn,
  SystemPromptComposer
}
import orca.subprocess.CliRunner
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.tools.claude.streamjson.OutboundMessage
import ox.Ox
import ox.channels.BufferCapacity

/** Claude Code backend. All calls — autonomous and interactive — drive a
  * stream-json subprocess through [[ClaudeConversation]]; the only difference
  * is the [[SessionMode]] passed to `openConversation` (autonomous omits the
  * ask_user MCP, interactive wires it). The prompt is injected as the first
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
    private[claude] val cwdForProbe: os.Path = os.pwd
)(using Ox, BufferCapacity)
    extends AgentBackend[BackendTag.ClaudeCode.type]:

  /** Return a sibling backend that, on [[ToolSet.NetworkOnly]] turns,
    * pre-approves `tools` (claude `--allowedTools` syntax). The configuration
    * seam behind `ClaudeAgent.withNetworkTools`; lives on the backend, not
    * `AgentConfig`, since the strings are claude-specific.
    */
  def withNetworkTools(tools: Seq[String]): ClaudeBackend =
    new ClaudeBackend(cli, tools, projectsDir, cwdForProbe)

  /** Best-effort probe: checks for the on-disk transcript file at
    * `<projectsDir>/<cwdSlug>/<id>.jsonl`. The project-dir slug is derived from
    * the working directory by replacing every `/` with `-` (e.g.
    * `/home/foo/orca` → `-home-foo-orca`). Returns `false` — safe re-seed —
    * when the file is absent, the projects dir doesn't exist yet, or the id
    * fails the [[orca.agents.isSafeSessionId]] guard (blocks path traversal
    * such as `../../etc/passwd`).
    */
  override def sessionExists(
      session: SessionId[BackendTag.ClaudeCode.type]
  ): Boolean =
    probeGuarded(SessionId.value(session)): id =>
      val slug = ClaudeBackend.cwdSlug(cwdForProbe)
      os.exists(projectsDir / slug / s"$id.jsonl")

  /** Tracks which session ids we've already claimed via `--session-id` so
    * subsequent calls use `--resume` (the CLI refuses to reuse `--session-id`
    * once the session exists).
    */
  private val sessions =
    new SessionRegistry.ClaimedOnce[BackendTag.ClaudeCode.type]

  // Claude's sessions live on disk (`~/.claude/projects/.../<id>.jsonl`) and
  // outlive the process, so the `--session-id` (claim) → `--resume` (continue)
  // decision must survive a resume. Wire the claim into the persist/rehydrate
  // hooks: `resumeWireId` lets the runtime record the claimed id (claude's
  // client id IS the wire id) into the progress log, and `registerSession`
  // re-claims it on rehydrate so a resumed task uses `--resume` rather than
  // re-creating an already-existing session id. Unlike pi, whose sessions live
  // in a deleteOnExit temp dir and are gone across runs, claude's are durable,
  // so it must participate in persist/rehydrate rather than always re-seeding.
  override def resumeWireId(
      client: SessionId[BackendTag.ClaudeCode.type]
  ): Option[SessionId[BackendTag.ClaudeCode.type]] =
    sessions.resumeWireId(client)

  override def registerSession(
      client: SessionId[BackendTag.ClaudeCode.type],
      server: SessionId[BackendTag.ClaudeCode.type]
  ): Unit =
    sessions.commitSuccess(client, server)

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.ClaudeCode.type],
      config: AgentConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): AgentResult[BackendTag.ClaudeCode.type] =
    val conv = openConversation(
      prompt = prompt,
      mode = SessionMode.Autonomous,
      session = session,
      config = config,
      workDir = workDir,
      outputSchema = outputSchema
    )
    // drainAndCommit commits only after a successful drain: a subprocess that
    // crashed before claude could register the session id (e.g. exit before
    // `system.init`) would otherwise leave the registry wedged, forcing a retry
    // to `--resume` a session claude never created.
    Conversations.drainAndCommit("claude", conv, session, sessions, events)

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.ClaudeCode.type],
      displayPrompt: String,
      config: AgentConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.ClaudeCode.type] =
    val conv = openConversation(
      prompt = prompt,
      mode = SessionMode.Interactive(displayPrompt),
      session = session,
      config = config,
      workDir = workDir,
      outputSchema = outputSchema
    )
    // Interactive has no in-backend drain to gate on; commit once the
    // conversation is up (the spawn succeeded, claude has parsed args).
    // A crash mid-conversation will still leave the mark in place, but
    // interactive sessions aren't auto-retried by the orchestrator —
    // the user reruns with a fresh `claude.newSession`.
    sessions.commitSuccess(session, session)
    conv

  /** Spawn `claude` in stream-json mode, write the opening user turn, close
    * stdin, and wrap the process in a live [[ClaudeConversation]]. Used by both
    * the interactive path ([[SessionMode.Interactive]]) and the autonomous path
    * ([[SessionMode.Autonomous]]).
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
      mode: SessionMode,
      session: SessionId[BackendTag.ClaudeCode.type],
      config: AgentConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.ClaudeCode.type] =
    // Allocate ask_user resources up front so we can close them
    // deterministically on a downstream failure. `None` for autonomous —
    // those calls don't expose the tool. Claude's `extras` deletes the
    // workDir-local `.orca-mcp-<port>.json` when the conversation ends.
    val (askUser, displayPrompt): (Option[AskUserSession], String) =
      mode match
        case SessionMode.Interactive(p) =>
          val resources = AskUserSession.allocate: server =>
            writeMcpConfig(server, workDir)
            List(
              ClaudeBackend.deleteFileResource(mcpConfigPath(server, workDir))
            )
          (Some(resources), p)
        case SessionMode.Autonomous => (None, "")
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
      // leave the registry wedged — a retry will still try `--session-id`.
      // Callers must not share a session id across concurrent calls;
      // `reviewAndFixLoop`'s parallel reviewer fan-out is safe because each
      // reviewer mints its own distinct id via `Agent.newSession`.
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
      includeAskUserHint: Boolean = false
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

  /** Returns an `AutoCloseable` that best-effort deletes the given file when
    * closed. Used as an `AskUserSession.extras` entry so each conversation's
    * `.orca-mcp-<port>.json` is removed when the conversation ends — without
    * this, long flows would accumulate orphan config files in `workDir`.
    */
  private[claude] def deleteFileResource(path: os.Path): AutoCloseable =
    () => if os.exists(path) then os.remove(path): Unit

  /** Fully-qualified tool name the agent uses, derived from the MCP server name
    * + the tool's bare slug. Always auto-approved on the interactive path — the
    * user is already typing an answer, no need for a y/n prompt first.
    */
  private[claude] val AskUserToolName: String =
    s"mcp__${AskUserMcpServer.ServerName}__${AskUserMcpServer.ToolSlug}"
