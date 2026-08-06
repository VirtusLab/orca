package orca.tools.claude

import java.util.concurrent.atomic.AtomicBoolean

import orca.OrcaDir
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
import orca.backend.mcp.{
  AskUserMcpServer,
  AskUserSession,
  GitHubMcpServer,
  McpHost,
  RepoMcpServer
}
import orca.tools.{GitTool, OsGitHubTool, OsGitTool}
import orca.tools.claude.streamjson.OutboundMessage
import ox.Ox

import scala.concurrent.duration.FiniteDuration

/** Claude Code backend. All calls — autonomous and interactive — drive a
  * stream-json subprocess through [[ClaudeConversation]]. The autonomous path
  * drains events and returns the awaited `AgentResult`; the interactive path
  * hands the `Conversation` back to the caller who runs `Interaction.drive`.
  *
  * A turn also stands up whichever host MCP servers it is entitled to — see
  * [[openConversation]]. Every one is a Netty binding whose lifetime tracks the
  * conversation, not the backend, so a long flow doesn't accumulate them.
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

  /** Return a sibling backend that, on [[ToolSet.NetworkOnly]] turns, adds
    * `tools` to the read-only `--tools` allowlist. Lives on the backend, not
    * `AgentConfig`, since the names are claude-specific.
    *
    * Rejects anything that is not a bare tool name. These used to be
    * `--allowedTools` patterns and could be command-scoped (`Bash(gh api:*)`);
    * `--tools` takes bare names and drops what it does not recognise silently,
    * exit 0, no warning. Without this check a flow script carrying the old
    * syntax would keep compiling, keep running, and grant nothing.
    *
    * Shares `closedFlag` with `this`: the sibling is a genuinely different
    * `AgentBackend` instance, so without threading the SAME flag through, a
    * handle derived here and leaked past flow-end would bypass the
    * use-after-close guard.
    */
  def withNetworkTools(tools: Seq[String]): ClaudeBackend =
    tools.filterNot(ClaudeBackend.BareToolName.matches) match
      case Nil =>
        new ClaudeBackend(cli, tools, projectsDir, workDir, closedFlag)
      case bad =>
        throw new IllegalArgumentException(
          s"withNetworkTools takes bare claude tool names; these are not: " +
            s"${bad.mkString(", ")}. Command-scoped entries like " +
            "\"Bash(gh api:*)\" belonged to the old --allowedTools mapping and " +
            "are silently ignored by --tools."
        )

  /** Claude's sessions live on disk (`~/.claude/projects/.../<id>.jsonl`) and
    * outlive the process, so it is durable: the claim survives a restart
    * (rehydrated on resume so a resumed task uses `--resume`), and existence is
    * a best-effort transcript-file probe. Because
    * [[SessionSupport.willContinue]] resolves the recorded mapping first, the
    * probe only runs for an id already known: a stray transcript for a
    * never-claimed id reports `false`, which is safe since the caller re-seeds.
    */
  val tag: BackendTag.ClaudeCode.type = BackendTag.ClaudeCode

  /** Backs the repo-read MCP tools. Derived from `workDir` rather than injected
    * so it cannot disagree with the directory claude is spawned in — the whole
    * point of the tools is to read the repository the agent is looking at.
    */
  private val git: GitTool = new OsGitTool(workDir)

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
    * stdin, and wrap the process in a live [[ClaudeConversation]]. orca sends
    * one message per process, so stdin closes straight away. The CLI answers
    * with stdin open either way; the close is what makes it exit, which ends
    * the reader on a turn that never settles.
    *
    * Three host MCP servers may be stood up, each gated on what the turn is:
    *
    *   - `ask_user` — `Interactive` only. An autonomous call has no renderer to
    *     answer the question, so exposing it would let the agent deadlock.
    *   - repo reads — any tier that loses the shell to `--tools`.
    *   - GitHub reads — `NetworkOnly` only, so reviewers stay network-free.
    *
    * Their config is written to [[ClaudeBackend.mcpConfigPath]] as one file and
    * passed via `--mcp-config`, and their tools are pre-approved by name. Each
    * is a session resource closed from `onFinalize` after the read loop drains;
    * if anything between allocation and conversation construction throws, they
    * are torn down (and the process SIGINTed if already spawned) so nothing
    * leaks.
    */
  private def openConversation(
      prompt: String,
      mode: ConversationMode,
      session: SessionId[BackendTag.ClaudeCode.type],
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.ClaudeCode.type] =
    // Allocate MCP resources up front so a downstream failure can close them
    // deterministically. `ask_user` is interactive-only (an autonomous call has
    // no renderer to answer it); the repo reads are the reverse — they exist to
    // give read-only turns back what `--tools` takes away.
    val displayPrompt = mode.displayPrompt
    val askUser: Option[AskUserSession] =
      Option.when(mode.isInteractive)(AskUserSession.allocate())
    val repoReads: Option[McpHost] =
      Option.when(ClaudeArgs.losesShell(config.tools))(RepoMcpServer.start(git))
    val githubReads: Option[McpHost] =
      Option.when(config.tools == ToolSet.NetworkOnly):
        GitHubMcpServer.start(new OsGitHubTool(cli, workDir))
    val mcpConfig = Option
      .when(askUser.isDefined || repoReads.isDefined || githubReads.isDefined):
        writeMcpConfig(askUser.map(_.server), repoReads, githubReads, session)
    val systemPromptFile = writeSystemPromptIfPresent(
      config,
      includeAskUserHint = askUser.isDefined,
      includeRepoHint = repoReads.isDefined,
      includeGitHubHint = githubReads.isDefined
    )
    // One list, handed to both sides: `open` releases it if the spawn or the
    // build fails, the conversation's `onFinalize` releases it on the happy
    // path. `askUser` stays separate — `ForkedConversation` owns it, because
    // the bridge must be errored before the read loop is torn down.
    val perTurn: List[AutoCloseable] =
      repoReads.toList ++ githubReads.toList ++
        mcpConfig.map(SubprocessSpawn.deleteFileResource) ++
        systemPromptFile.map(SubprocessSpawn.deleteFileResource)
    SubprocessSpawn.open("claude stream-json", askUser.toList ++ perTurn) {
      // `autoApproveAlso` reaches `--allowedTools` only on `Full`; the
      // read-only tiers ignore `autoApprove` entirely, so the name also goes
      // through `mcpTools` below. Both, because `Full` needs the config route
      // and the read-only tiers need the flag route.
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
        mcpConfig = mcpConfig,
        networkTools = networkTools,
        mcpTools = askUser.toSeq.map(_ => ClaudeBackend.AskUserToolName) ++
          repoReads.toSeq.flatMap(_ => ClaudeBackend.RepoToolNames) ++
          githubReads.toSeq.flatMap(_ => ClaudeBackend.GitHubToolNames)
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
        askUser = askUser,
        resources = perTurn
      )
    }

  /** Write this conversation's MCP config, listing whichever host servers it
    * stood up, and return its path.
    *
    * Each `timeout` raises claude's per-server tool-call limit from its 60s
    * default. For `ask_user` that is load-bearing: without it claude gives up
    * if the human takes more than 60s to answer, then fires a follow-up and the
    * user answers twice. `AskUserMcpServer.ToolTimeout` is rendered in three
    * places (claude JSON ms / codex TOML sec / gemini settings.json ms); keep
    * them in sync.
    */
  private def writeMcpConfig(
      askUser: Option[McpHost],
      repoReads: Option[McpHost],
      githubReads: Option[McpHost],
      session: SessionId[BackendTag.ClaudeCode.type]
  ): os.Path =
    val entries =
      askUser.map(
        entryJson(AskUserMcpServer.ServerName, _, AskUserMcpServer.ToolTimeout)
      ) ++
        repoReads.map(
          entryJson(RepoMcpServer.ServerName, _, RepoMcpServer.ToolTimeout)
        ) ++
        githubReads.map(
          entryJson(GitHubMcpServer.ServerName, _, GitHubMcpServer.ToolTimeout)
        )
    val _ = OrcaDir.ensureCache(workDir)
    val path = ClaudeBackend.mcpConfigPath(workDir, session)
    // `os.write` is CREATE_NEW — it refuses a leaf symlink, which
    // `os.write.over` would follow — so a leftover from an earlier hard kill
    // has to be removed first. `os.remove` unlinks a symlink rather than
    // following it.
    val _ = os.remove(path)
    os.write(path, s"""{"mcpServers":{${entries.mkString(",")}}}""")
    path

  private def entryJson(
      name: String,
      host: McpHost,
      timeout: FiniteDuration
  ): String =
    s""""$name":{"type":"http","url":"${host.url}","timeout":${timeout.toMillis}}"""

  /** Build the per-session system-prompt file: compose `config.systemPrompt`
    * with whichever MCP hints apply, then write to a JVM temp file
    * (auto-cleaned on exit) rather than the user's workDir — it's purely an IPC
    * mechanism, read once via `--append-system-prompt-file`.
    */
  private def writeSystemPromptIfPresent(
      config: AgentConfig,
      includeAskUserHint: Boolean,
      includeRepoHint: Boolean,
      includeGitHubHint: Boolean
  ): Option[os.Path] =
    val hints =
      Option.when(includeAskUserHint)(AskUserMcpServer.Hint) ++
        Option.when(includeRepoHint)(RepoMcpServer.Hint) ++
        Option.when(includeGitHubHint)(GitHubMcpServer.Hint)
    SystemPromptComposer
      .combine(config, hints.reduceOption(_ + "\n\n" + _))
      .map: text =>
        os.temp(prefix = "orca-system-prompt-", suffix = ".md", contents = text)

object ClaudeBackend:

  /** Path of the MCP config file advertising this conversation's host servers.
    * Named with the session id so two conversations sharing a `workDir` don't
    * overwrite each other's config.
    *
    * Under the self-ignoring `.orca/cache/` rather than at the workDir root: a
    * hard kill skips the deletion resource, and a leftover at the root is swept
    * into the next stage's `git add -A`. Still inside the working tree, so a
    * sandboxed claude that denies reads outside its worktree can read it.
    */
  private[claude] def mcpConfigPath(
      workDir: os.Path,
      session: SessionId[BackendTag.ClaudeCode.type]
  ): os.Path =
    OrcaDir.cachePath(workDir) / s"mcp-${session.value}.json"

  /** Derives the project-directory slug that claude uses under
    * `~/.claude/projects/`: replaces every `/` in the absolute path with `-`.
    * E.g. `/home/foo/bar` → `-home-foo-bar`.
    */
  private[claude] def cwdSlug(cwd: os.Path): String =
    cwd.toString.replace('/', '-')

  /** Built-in tools added to `ClaudeArgs.ReadOnlyTools` on
    * [[ToolSet.NetworkOnly]] turns. Bare tool names only — `--tools` takes no
    * command scoping, so there is no `gh` entry. Nothing replaces it: measured
    * planner use of `gh` was zero
    * (`docs/research/run-cost/12-reviewer-tool-surface.md` §5) and orca reads
    * issues host-side via `GitHubTool.readIssue`. Flows wanting a different set
    * pass their own via `claude.withNetworkTools(...)`.
    */
  private[claude] val DefaultNetworkTools: Seq[String] =
    Seq("WebFetch", "WebSearch")

  /** What `--tools` accepts: a bare built-in name. Not MCP names — those pass
    * `--tools` unfiltered, so listing one there does nothing.
    */
  private val BareToolName = "[A-Za-z][A-Za-z0-9]*".r

  /** Fully-qualified tool name (MCP server name + tool slug). Always
    * auto-approved on the interactive path — the user is already typing an
    * answer, no need for a y/n prompt first.
    */
  private[claude] val AskUserToolName: String =
    qualifiedToolName(AskUserMcpServer.ServerName, AskUserMcpServer.ToolSlug)

  /** The repo-read tools, qualified the way claude advertises them. Read-only
    * turns pass these to `--allowedTools`: `--tools` bounds what exists, but an
    * MCP call still needs approval, which an autonomous turn cannot give.
    */
  private[claude] val RepoToolNames: Seq[String] =
    RepoMcpServer.ToolSlugs.map(qualifiedToolName(RepoMcpServer.ServerName, _))

  /** The GitHub reads, qualified. `NetworkOnly` only — reviewers stay
    * network-free.
    */
  private[claude] val GitHubToolNames: Seq[String] =
    GitHubMcpServer.ToolSlugs.map(
      qualifiedToolName(GitHubMcpServer.ServerName, _)
    )

  /** How claude names an MCP tool once its server is registered. */
  private def qualifiedToolName(server: String, slug: String): String =
    s"mcp__${server}__$slug"

  /** Tool name the claude CLI injects for `--json-schema` structured output:
    * the model "exits" the turn by calling this tool with the payload as its
    * input. The conversation suppresses that echo — the payload reaches the
    * caller via the result message as `OrcaEvent.StructuredResult`, so
    * rendering the tool call too would show the same JSON twice.
    */
  private[claude] val StructuredOutputToolName: String = "StructuredOutput"
