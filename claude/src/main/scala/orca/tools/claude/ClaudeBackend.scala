package orca.tools.claude

import java.util.concurrent.atomic.AtomicBoolean

import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  EnforcementCell,
  EnforcementNotice,
  StructuredOutputMode,
  ToolSet,
  TurnDispatch
}
import orca.backend.{
  Conversation,
  TurnRequest,
  AgentBackend,
  IdScheme,
  SessionSupport,
  SubprocessSpawn,
  SystemPromptComposer,
  TurnResources
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
import ox.{Ox, ResourceScope}

import scala.concurrent.duration.FiniteDuration

/** The registration facts a turn needs from one host-served MCP server, paired
  * with its live host.
  */
private[claude] final case class TurnMcp(
    name: String,
    slugs: Seq[String],
    timeout: FiniteDuration,
    hint: String,
    host: McpHost
)

/** Claude Code backend. All calls — autonomous and interactive — drive a
  * stream-json subprocess through [[ClaudeConversation]].
  *
  * A turn also stands up whichever host MCP servers it is entitled to — see
  * [[open]]. Every one is a Netty binding whose lifetime tracks the
  * conversation, not the backend, so a long flow doesn't accumulate them.
  */
private[orca] class ClaudeBackend(
    cli: CliRunner,
    networkTools: Seq[String] = ClaudeBackend.DefaultNetworkTools,
    private[claude] val projectsDir: os.Path = os.home / ".claude" / "projects",
    /** Shared between the spawn path ([[open]]) and the existence probe (see
      * [[sessions]]): claude writes a session's transcript under
      * `<projectsDir>/<cwdSlug(workDir)>/<id>.jsonl`, so both sides reading the
      * SAME field keeps the probe honest. The `os.pwd` default serves only
      * bare/test construction; the runtime passes the flow's real `workDir`.
      */
    override val workDir: os.Path = os.pwd,
    /** Threaded into [[AgentBackend]]'s `closedFlag` and `enforcementNotice`.
      * Bare construction gets fresh ones; [[withNetworkTools]] passes THIS
      * instance's, so the sibling shares one close latch and one notice log
      * with its parent — see `AgentBackend` for why each must be shared.
      */
    sharedClosedFlag: AtomicBoolean = new AtomicBoolean(false),
    sharedNotice: EnforcementNotice = new EnforcementNotice
) extends AgentBackend[BackendTag.ClaudeCode.type](
      sharedClosedFlag,
      sharedNotice
    ):

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
    * Also rejects the write-capable builtins: a `NetworkOnly` turn puts these
    * names on both `--tools` and `--allowedTools`, so passing one here hands
    * back an auto-approved shell while the tier still reports `Hard`.
    *
    * Shares `closedFlag` and `enforcementNotice` with `this`: the sibling is a
    * genuinely different `AgentBackend` instance, so without threading the SAME
    * values through, a handle derived here and leaked past flow-end would
    * bypass the use-after-close guard, and every enforcement notice would be
    * given a second time.
    */
  def withNetworkTools(tools: Seq[String]): ClaudeBackend =
    ClaudeBackend.rejectNonBareNames(tools)
    ClaudeBackend.rejectWriteCapable(tools)
    new ClaudeBackend(
      cli,
      tools,
      projectsDir,
      workDir,
      closedFlag,
      enforcementNotice
    )

  /** Claude's sessions live on disk (`~/.claude/projects/.../<id>.jsonl`) and
    * outlive the process, so it is durable: the claim survives a restart
    * (rehydrated on resume so a resumed task uses `--resume`), and existence is
    * a best-effort transcript-file probe. The probe also answers for a claimed
    * id with no mapping recorded — a transcript under an id orca minted is one
    * an earlier run put on the wire, and claiming it again is what the CLI
    * refuses.
    */
  val tag: BackendTag.ClaudeCode.type = BackendTag.ClaudeCode

  /** Backs the repo-read MCP tools. Derived from `workDir` rather than injected
    * so it cannot disagree with the directory claude is spawned in — the whole
    * point of the tools is to read the repository the agent is looking at.
    */
  private val git: GitTool = new OsGitTool(workDir)

  export ClaudeArgs.enforcementCell

  override def structuredOutputMode: StructuredOutputMode =
    ClaudeBackend.StructuredOutputDelivery

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

  /** Spawn `claude` in stream-json mode, write the opening user turn, close
    * stdin, and wrap the process in a live [[ClaudeConversation]]. orca sends
    * one message per process, so stdin closes straight away. The CLI answers
    * with stdin open either way; the close is what makes it exit, which ends
    * the reader on a turn that never settles.
    *
    * Host MCP servers may be stood up: `ask_user` on an `Interactive` turn only
    * — an autonomous call has no renderer to answer the question, so exposing
    * it would let the agent deadlock — plus whatever [[turnServers]] the tier
    * is entitled to.
    *
    * Their config is written to one temp file (see [[writeMcpConfig]]) and
    * passed via `--mcp-config`, and their tools are pre-approved by name. The
    * servers and temp files are released when the turn scope ends.
    */
  override protected[orca] def open(
      turn: TurnRequest[BackendTag.ClaudeCode.type]
  )(using Ox): Conversation[BackendTag.ClaudeCode.type] =
    import turn.*
    val displayPrompt = mode.displayPrompt
    val askUser: Option[AskUserSession] =
      Option.when(mode.isInteractive)(AskUserSession.allocate())
    val servers = turnServers(config.tools)
    val mcpConfig = Option.when(askUser.isDefined || servers.nonEmpty):
      writeMcpConfig(askUser.map(_.server), servers)
    val systemPromptFile = writeSystemPrompt(
      config,
      hints = askUser.map(_ => AskUserMcpServer.Hint).toList ++
        servers.map(_.hint)
    )
    SubprocessSpawn.open("claude stream-json", events) {
      // `autoApproveAlso` reaches `--allowedTools` only on `Full`; the
      // read-only tiers ignore `autoApprove` entirely, so the name also goes
      // through `mcpTools` below. Both, because `Full` needs the config route
      // and the read-only tiers need the flag route.
      val effectiveConfig =
        if askUser.isDefined then
          config.autoApproveAlso(ClaudeBackend.AskUserToolName)
        else config
      val args = ClaudeArgs.streamJson(
        effectiveConfig,
        Some(systemPromptFile),
        dispatch = dispatch,
        outputSchema,
        mcpConfig = mcpConfig,
        networkTools = networkTools,
        mcpTools = askUser.toSeq.map(_ => ClaudeBackend.AskUserToolName) ++
          servers.flatMap(s =>
            ClaudeBackend.qualifiedToolNames(s.name, s.slugs)
          )
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

  /** Stand up the host MCP servers a turn with `tools` is entitled to — the
    * only place a tier is mapped to a server. Everything else the turn derives
    * from a server maps over the returned list.
    */
  private def turnServers(tools: ToolSet)(using Ox): List[TurnMcp] =
    // The read-only tiers drop `Bash` along with the write tools, so the host
    // serves the git reads back. `NoTools` gets no repo access at all.
    val readOnlyTier = tools match
      case ToolSet.ReadOnly | ToolSet.NetworkOnly => true
      case ToolSet.Full | ToolSet.NoTools         => false
    val repoReads = Option.when(readOnlyTier):
      TurnMcp(
        name = RepoMcpServer.ServerName,
        slugs = RepoMcpServer.ToolSlugs,
        timeout = RepoMcpServer.ToolTimeout,
        hint = RepoMcpServer.Hint,
        host = RepoMcpServer.start(git)
      )
    // `NetworkOnly` only, so reviewers stay network-free.
    val githubReads = Option.when(tools.hasScopedNetwork):
      TurnMcp(
        name = GitHubMcpServer.ServerName,
        slugs = GitHubMcpServer.ToolSlugs,
        timeout = GitHubMcpServer.ToolTimeout,
        hint = GitHubMcpServer.Hint,
        host = GitHubMcpServer.start(new OsGitHubTool(cli, workDir))
      )
    List(repoReads, githubReads).flatten

  /** Write this conversation's MCP config, listing whichever host servers it
    * stood up, to a JVM temp file and return its path.
    *
    * Outside `workDir` like the system-prompt file, so a hard kill that skips
    * the deletion leaves nothing a stage commit can sweep up. Probed 2026-09-23
    * with claude 2.1.280: `claude -p --mcp-config /tmp/<file>` with
    * `{"sandbox":{"enabled":true}}` in `--settings` connected to the `http`
    * server listed there and called its tool.
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
      servers: List[TurnMcp]
  )(using ResourceScope): os.Path =
    val entries =
      askUser.map(host =>
        AskUserMcpServer.ServerName ->
          McpConfig.HttpServer(host.url, AskUserMcpServer.ToolTimeout)
      ) ++
        servers.map(s => s.name -> McpConfig.HttpServer(s.host.url, s.timeout))
    TurnResources.tempFile(
      os.temp(
        prefix = "orca-mcp-",
        suffix = ".json",
        contents = McpConfig.render(entries.toMap)
      )
    )

  /** Build the per-session system-prompt file: compose `config.systemPrompt`
    * with whichever MCP hints apply, then write to a temp file removed at turn
    * end rather than the user's workDir — it's purely an IPC mechanism, read
    * once via `--append-system-prompt-file`.
    */
  private def writeSystemPrompt(
      config: AgentConfig,
      hints: List[String]
  )(using ResourceScope): os.Path =
    TurnResources.tempFile(
      os.temp(
        prefix = "orca-system-prompt-",
        suffix = ".md",
        contents = SystemPromptComposer
          .combine(config, hints.reduceOption(_ + "\n\n" + _))
      )
    )

object ClaudeBackend:

  /** Derives the project-directory slug that claude uses under
    * `~/.claude/projects/`. E.g. `/home/foo/.bar` → `-home-foo--bar`.
    */
  private[claude] def cwdSlug(cwd: os.Path): String =
    val path = cwd.toString
    // Mirrors claude's JS: each UTF-16 unit outside [a-zA-Z0-9] becomes `-`;
    // past 200 chars, the slug is cut and suffixed with the base-36 absolute
    // value of the path's 32-bit `(h << 5) - h + c` hash (= Java's hashCode).
    val slug = path.map(c => if c.isLetterOrDigit && c < 128 then c else '-')
    if slug.length <= MaxSlugLength then slug
    else
      val hash = java.lang.Long.toString(math.abs(path.hashCode.toLong), 36)
      s"${slug.take(MaxSlugLength)}-$hash"

  private val MaxSlugLength = 200

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

  /** Builtins [[withNetworkTools]] refuses: each writes, shells out, or drives
    * a shell, and `withNetworkTools` exists only to add network reads. Probed
    * 2026-08-08, claude 2.1.226: only `Bash`, `Monitor`, `Write`, `Edit` and
    * `NotebookEdit` are still in the built-in set; the rest are kept because a
    * stale name here is harmless while a missing one is not.
    */
  private val WriteCapableTools: Set[String] = Set(
    "Bash",
    "BashOutput",
    "KillBash",
    "KillShell",
    "Monitor",
    "Write",
    "Edit",
    "MultiEdit",
    "NotebookEdit"
  )

  private def rejectNonBareNames(tools: Seq[String]): Unit =
    val bad = tools.filterNot(BareToolName.matches)
    if bad.nonEmpty then
      throw new IllegalArgumentException(
        "withNetworkTools takes bare claude tool names; these are not: " +
          s"${bad.mkString(", ")}. Command-scoped entries like " +
          "\"Bash(gh api:*)\" belonged to the old --allowedTools mapping and " +
          "are silently ignored by --tools."
      )

  private def rejectWriteCapable(tools: Seq[String]): Unit =
    val bad = tools.filter(WriteCapableTools.contains)
    if bad.nonEmpty then
      throw new IllegalArgumentException(
        "withNetworkTools adds network reads to a NetworkOnly turn; these " +
          s"write or shell out: ${bad.mkString(", ")}. Use ToolSet.Full if " +
          "the agent needs to write or run commands."
      )

  /** Fully-qualified tool name (MCP server name + tool slug). Always
    * auto-approved on the interactive path — the user is already typing an
    * answer, no need for a y/n prompt first.
    */
  private[claude] val AskUserToolName: String =
    qualifiedToolName(AskUserMcpServer.ServerName, AskUserMcpServer.ToolSlug)

  /** A host server's tools. A turn that stands one up passes these to
    * `--allowedTools`: `--tools` bounds what exists, but an MCP call still
    * needs approval, which an autonomous turn cannot give.
    */
  private[claude] def qualifiedToolNames(
      server: String,
      slugs: Seq[String]
  ): Seq[String] =
    slugs.map(qualifiedToolName(server, _))

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

  /** Shared by [[ClaudeBackend.structuredOutputMode]] and
    * [[ClaudeConversation]], so prompt assembly and the drain can't disagree
    * about how the payload arrives.
    */
  private[claude] val StructuredOutputDelivery: StructuredOutputMode =
    StructuredOutputMode.Tool
