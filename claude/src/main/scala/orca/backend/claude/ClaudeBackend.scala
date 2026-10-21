package orca.backend.claude

import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  EnforcementCell,
  Model,
  StructuredOutputMode,
  ToolSet,
  TurnDispatch
}
import orca.backend.{
  AskUserChannel,
  LiveTurn,
  TurnRequest,
  AgentBackend,
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
import orca.effect.{FileSystem, Network}
import orca.tools.{GitTool, OsGitHubTool}
import orca.backend.claude.streamjson.OutboundMessage
import orca.safe.concurrency.defaultBufferCapacity
import orca.safe.prelude.given
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
  * stream-json subprocess through [[ClaudeTurn]].
  *
  * A turn also stands up whichever host MCP servers it is entitled to — see
  * [[open]]. Every one is a Netty binding whose lifetime tracks the turn, not
  * the backend, so a long flow doesn't accumulate them.
  */
private[orca] class ClaudeBackend(
    net: Network,
    fs: FileSystem,
    cli: CliRunner,
    /** Builds the `GitTool` behind the repo-read MCP tools, given [[workDir]]:
      * they read the repository the agent is looking at.
      */
    gitAt: os.Path -> GitTool,
    /** [[ClaudeBackend.transcriptProbe]] in production. */
    transcriptExists: (os.Path, String) -> Boolean,
    /** Shared between the spawn path ([[open]]) and the existence probe (see
      * [[sessions]]): claude writes a session's transcript under a directory
      * derived from its cwd, so both sides reading the SAME field keeps the
      * probe honest.
      */
    override val workDir: os.Path
) extends AgentBackend[BackendTag.ClaudeCode.type]:

  /** Claude's sessions live on disk (`~/.claude/projects/.../<id>.jsonl`) and
    * outlive the process, so it is durable: the claim survives a restart
    * (rehydrated on resume so a resumed task uses `--resume`), and existence is
    * a best-effort transcript-file probe. The probe also answers for a claimed
    * id with no mapping recorded — a transcript under an id orca minted is one
    * an earlier attempt put on the wire, and claiming it again is what the CLI
    * refuses.
    */
  val tag: BackendTag.ClaudeCode.type = BackendTag.ClaudeCode

  export ClaudeArgs.enforcementCell

  private val git: GitTool = gitAt(workDir)

  override def structuredOutputMode: StructuredOutputMode =
    StructuredOutputMode.Tool

  def cheapModel(leading: Option[Model]): Option[Model] =
    Some(ClaudeModels.Haiku)

  /** The sole session handle. [[IdScheme.ClientClaimed]]: ids are claimed via
    * `--session-id` so subsequent calls use `--resume` (the CLI refuses to
    * reuse `--session-id` once the session exists).
    */
  val sessions: SessionSupport[BackendTag.ClaudeCode.type] =
    SessionSupport.durable(
      IdScheme.ClientClaimed,
      id => transcriptExists(workDir, id)
    )

  /** Spawn `claude` in stream-json mode, write the opening user message, close
    * stdin, and wrap the process in a [[ClaudeTurn]]. orca sends one message
    * per process, so stdin closes straight away. The CLI answers with stdin
    * open either way; the close is what makes it exit, which ends the reader on
    * a turn that never settles.
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
  )(using Ox): LiveTurn[BackendTag.ClaudeCode.type] =
    import turn.*
    val askUser: Option[AskUserSession] =
      Option.when(mode.isInteractive)(AskUserSession.allocate(net))
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
        mcpTools = askUser.toSeq.map(_ => ClaudeBackend.AskUserToolName) ++
          servers.flatMap(s =>
            ClaudeBackend.qualifiedToolNames(s.name, s.slugs)
          )
      )
      cli.spawnPiped(args, cwd = workDir)
    } { process =>
      process.writeLine(OutboundMessage.userText(prompt))
      process.closeStdin()
      ClaudeTurn(
        process,
        openingPrompt = mode.openingPrompt,
        outputSchema = outputSchema,
        askUser =
          askUser.fold(AskUserChannel.Unavailable)(AskUserChannel.Mcp(_))
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
        host = RepoMcpServer.start(net, git)
      )
    // `NetworkOnly` only, so reviewers stay network-free.
    val githubReads = Option.when(tools.hasScopedNetwork):
      TurnMcp(
        name = GitHubMcpServer.ServerName,
        slugs = GitHubMcpServer.ToolSlugs,
        timeout = GitHubMcpServer.ToolTimeout,
        hint = GitHubMcpServer.Hint,
        host = GitHubMcpServer.start(net, new OsGitHubTool(cli, workDir))
      )
    List(repoReads, githubReads).flatten

  /** Write this turn's MCP config, listing whichever host servers it stood up,
    * to a JVM temp file and return its path.
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
    fs.tempFile(
      McpConfig.render(entries.toMap),
      prefix = "orca-mcp-",
      suffix = ".json"
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
    fs.tempFile(
      SystemPromptComposer.combine(config, hints.reduceOption(_ + "\n\n" + _)),
      prefix = "orca-system-prompt-",
      suffix = ".md"
    )

object ClaudeBackend:

  /** Whether claude has a transcript for session `id` started in `workDir`;
    * `projectsDir` is claude's `~/.claude/projects`.
    */
  def transcriptProbe(
      fs: FileSystem,
      projectsDir: os.Path
  ): (os.Path, String) ->{fs} Boolean =
    (workDir, id) => fs.exists(projectsDir / cwdSlug(workDir) / s"$id.jsonl")

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
    * input. The decoder suppresses that echo — the payload reaches the caller
    * via the result message as `OrcaEvent.StructuredResult`, so rendering the
    * tool call too would show the same JSON twice.
    */
  private[claude] val StructuredOutputToolName: String = "StructuredOutput"
