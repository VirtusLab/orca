package orca.tools.codex

import orca.agents.{
  AutoApprove,
  BackendTag,
  EnforcementCell,
  StructuredOutputMode,
  ToolSet,
  TurnDispatch
}
import orca.backend.{
  Conversation,
  TurnRequest,
  Dispatch,
  AgentBackend,
  IdScheme,
  SessionSupport,
  SubprocessSpawn,
  TurnResources,
  SystemPromptComposer
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.CliRunner
import ox.{Ox, ResourceScope}

/** Codex backend. Both autonomous and interactive paths drive `codex exec
  * --json` over stdio: stdout JSONL is parsed into [[InboundEvent]]s, and the
  * assistant message preceding `turn.completed` becomes the result. See
  * [[../../../adr/0007-codex-exec-jsonl-driver.md ADR 0007]] for the shape of
  * the protocol and the rationale for not using the experimental WebSocket
  * app-server.
  *
  * Both modes wrap the subprocess in a [[CodexConversation]]. Multi-turn:
  * subsequent `runAutonomous` / `runInteractive` calls with the same session id
  * route through `codex exec resume <server-id>` via [[sessions]]
  * ([[IdScheme.ServerMinted]]).
  *
  * Interactive calls additionally stand up an `ask_user` MCP host bridge
  * ([[AskUserMcpServer]]) on an ephemeral port and register it with codex via
  * the top-level `-c mcp_servers.orca.url=…` config override, so the agent can
  * call `ask_user` to surface a clarifying question to the user. Autonomous
  * calls skip the bridge entirely.
  */
private[orca] class CodexBackend(
    cli: CliRunner,
    private[codex] val sessionsDir: os.Path = os.home / ".codex" / "sessions",
    /** Fixed at construction; every spawn (`open`) runs in this directory. The
      * `os.pwd` default serves bare/test construction; the runtime
      * (`CodexAgents.default`) passes the flow's real `workDir`.
      */
    override val workDir: os.Path = os.pwd
) extends AgentBackend[BackendTag.Codex.type]:

  /** Codex's threads are server-side and durable: the probe walks
    * [[sessionsDir]] for a file matching `rollout-*-<server-id>.jsonl` (the
    * caller's stable id never appears in a rollout filename).
    *
    * `false` results whenever no server id is mapped — including one that
    * failed the [[orca.agents.SessionId.isSafe]] guard (blocks regex injection;
    * e.g. `.*` would match every rollout file), the map not yet rehydrated, or
    * the sessions dir missing.
    *
    * Some machines' codex uses SQLite (`~/.codex/state_5.sqlite`) instead of
    * `rollout-*.jsonl`; with no matching files the probe returns `false` →
    * re-seed, always safe.
    */
  val tag: BackendTag.Codex.type = BackendTag.Codex

  export CodexArgs.enforcementCell

  /** `--output-schema` constrains the FINAL MESSAGE text — the reply text is
    * still the JSON value orca parses; there is no structured-output tool.
    * Probed against codex-cli 0.145.0 (2026-08-09): `codex exec --json
    * --output-schema` answered a bare question with the JSON as its
    * `agent_message` text. Only the fresh turn carries the flag —
    * [[CodexArgs.execResume]] leaves it off — so the prompt keeps the raw-JSON
    * contract for every turn.
    */
  override def structuredOutputMode: StructuredOutputMode =
    StructuredOutputMode.RawText

  /** The sole session handle. [[IdScheme.ServerMinted]]: the client-allocated
    * id (the UUID the caller passes around) maps to codex's server-allocated
    * thread id (learned from `thread.started`), so subsequent calls dispatch
    * through `codex exec resume <server-id>`.
    */
  val sessions: SessionSupport[BackendTag.Codex.type] =
    SessionSupport.durable(
      IdScheme.ServerMinted,
      id =>
        os.exists(sessionsDir) && os.walk
          .stream(sessionsDir)
          .exists(p =>
            p.last.startsWith("rollout-") && p.last.endsWith(s"-$id.jsonl")
          )
    )

  /** Spawn `codex exec --json` (fresh) or `codex exec resume <server-id>`
    * (continuation), per `dispatch`, and wrap the process in a live
    * [[CodexConversation]]. On a fresh spawn the post-drain commit records the
    * client→server mapping.
    *
    * `Interactive` mode wires the MCP `ask_user` tool: stand up the bridge +
    * Netty server, hand its URL to `CodexArgs` for the `-c mcp_servers.orca`
    * override, fold the system-prompt hint into the user prompt (codex has no
    * `--append-system-prompt`), and hand the bridge to `CodexConversation` to
    * surface `UserQuestion` events. `Autonomous` skips all of it. The server
    * and the schema file are released when the turn scope ends.
    */
  override protected[orca] def open(
      turn: TurnRequest[BackendTag.Codex.type]
  )(using Ox): Conversation[BackendTag.Codex.type] =
    import turn.*
    val schemaFile = writeSchemaIfPresent(outputSchema)
    val askUser: Option[AskUserSession] =
      Option.when(mode.isInteractive)(AskUserSession.allocate())
    SubprocessSpawn.open("codex", events) {
      // codex `exec` has no `--system-prompt` flag (it picks up `AGENTS.md`
      // files for static instructions), so fold the composed system prompt into
      // the user prompt.
      val finalPrompt = SystemPromptComposer.foldIntoPrompt(
        config,
        prompt,
        extraHint = Option.when(askUser.isDefined)(AskUserMcpServer.Hint)
      )
      val mcpUrl = askUser.map(_.server.url)
      val args = dispatch match
        case Dispatch.Resume(serverId, _) =>
          CodexArgs.execResume(
            serverId,
            finalPrompt,
            config,
            mcpServerUrl = mcpUrl
          )
        case Dispatch.Fresh(_) =>
          CodexArgs.exec(
            finalPrompt,
            config,
            schemaFile,
            workDir,
            mcpServerUrl = mcpUrl
          )
      cli.spawnPiped(args, cwd = workDir, pipeStderr = true)
    } { process =>
      // codex doesn't accept user turns over stdin once the prompt is
      // argv-supplied; close immediately so the child stops waiting on EOF.
      process.closeStdin()
      CodexConversation(
        process,
        initialPrompt = mode.openingPrompt,
        outputSchema = outputSchema,
        askUser = askUser,
        configuredModel = config.model
      )
    }

  /** Write the `--output-schema` payload (if any) to a unique temp file OUTSIDE
    * the working tree — never `workDir` — so it can't race a concurrent
    * structured call (the reviewer fan-out) or get swept into a flow's `git add
    * -A`. Removed when the turn scope ends.
    */
  private def writeSchemaIfPresent(schema: Option[String])(using
      ResourceScope
  ): Option[os.Path] =
    schema.map: body =>
      TurnResources.tempFile(
        body,
        prefix = "orca-codex-schema-",
        suffix = ".json"
      )
