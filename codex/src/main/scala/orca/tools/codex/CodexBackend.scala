package orca.tools.codex

import orca.events.OrcaListener
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  SessionId,
  StructuredOutputMode,
  ToolSet
}
import orca.backend.{
  Conversation,
  Conversations,
  Dispatch,
  AgentBackend,
  AgentResult,
  ConversationMode,
  IdScheme,
  SessionSupport,
  SubprocessSpawn,
  SystemPromptComposer
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.CliRunner
import ox.Ox

/** Codex backend. Both autonomous and interactive paths drive `codex exec
  * --json` over stdio: stdout JSONL is parsed into [[InboundEvent]]s, and the
  * assistant message preceding `turn.completed` becomes the result. See
  * [[../../../adr/0007-codex-exec-jsonl-driver.md ADR 0007]] for the shape of
  * the protocol and the rationale for not using the experimental WebSocket
  * app-server.
  *
  * Both modes wrap the subprocess in a [[CodexConversation]]; the autonomous
  * path drains it internally via [[orca.backend.Conversations.drainAutonomous]]
  * while the interactive path returns the conversation for an `Interaction` to
  * drive. Multi-turn: subsequent `runAutonomous` / `runInteractive` calls with
  * the same session id route through `codex exec resume <server-id>` via
  * [[sessions]] ([[IdScheme.ServerMinted]]).
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
    /** Fixed at construction; every spawn (`openConversation`) runs in this
      * directory. The `os.pwd` default serves bare/test construction; the
      * runtime (`CodexAgents.default`) passes the flow's real `workDir`.
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

  override def enforcement(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): Enforcement =
    CodexArgs.enforcement(tools, autoApprove)

  /** `--output-schema` constrains the FINAL MESSAGE text — the reply text is
    * still the JSON value orca parses; there is no structured-output tool.
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

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Codex.type],
      config: AgentConfig,
      events: OrcaListener,
      outputSchema: Option[String]
  ): AgentResult[BackendTag.Codex.type] =
    // Records the client→server mapping so a follow-up call on this client id
    // resumes the right thread; the result carries the server thread id as its
    // wireId, and the caller keeps using the client id.
    Conversations.runAutonomous(session, sessions, events):
      openConversation(
        prompt = prompt,
        mode = ConversationMode.Autonomous,
        session = session,
        config = config,
        // Forwarded so (a) `conv.outputSchema` signals structured mode to the
        // drain (suppressing the raw JSON payload from the user log) and (b)
        // `--output-schema` enforces the contract on the codex side too. A
        // resume can't pass `--output-schema`, so it falls back to prompt-only
        // enforcement.
        outputSchema = outputSchema
      )

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Codex.type],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.Codex.type] =
    openConversation(
      prompt,
      mode = ConversationMode.Interactive(displayPrompt),
      session = session,
      config = config,
      outputSchema = outputSchema
    )

  /** Spawn `codex exec --json` (fresh) or `codex exec resume <server-id>`
    * (continuation), and wrap the process in a live [[CodexConversation]]. The
    * fresh-vs-resume decision comes from `sessions.dispatchFor`; on a fresh
    * spawn the post-drain commit records the client→server mapping.
    *
    * `Interactive` mode wires the MCP `ask_user` tool: stand up the bridge +
    * Netty server, hand its URL to `CodexArgs` for the `-c mcp_servers.orca`
    * override, fold the system-prompt hint into the user prompt (codex has no
    * `--append-system-prompt`), and hand the bridge to `CodexConversation` to
    * surface `UserQuestion` events and close the binding on finalize.
    * `Autonomous` skips all of it. Any throw before conversation construction
    * tears down the server so no Netty binding leaks.
    */
  private def openConversation(
      prompt: String,
      mode: ConversationMode,
      session: SessionId[BackendTag.Codex.type],
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.Codex.type] =
    // Write the schema temp file FIRST — before any resource is allocated — so
    // a temp-write failure can't leak the Netty bridge `AskUserSession.allocate()`
    // would spin up. Threaded into `resources` (failure-path cleanup) and the
    // conversation below (success-path cleanup via `onFinalize`).
    val schemaFile = writeSchemaIfPresent(outputSchema)
    val displayPrompt = mode.displayPrompt
    val askUser: Option[AskUserSession] =
      Option.when(mode.isInteractive)(AskUserSession.allocate())
    SubprocessSpawn.open(
      "codex",
      askUser.toList ++ schemaFile
        .map(SubprocessSpawn.deleteFileResource)
        .toList
    ) {
      // codex `exec` has no `--system-prompt` flag (it picks up `AGENTS.md`
      // files for static instructions), so fold the composed system prompt into
      // the user prompt.
      val finalPrompt = SystemPromptComposer.foldIntoPrompt(
        config,
        prompt,
        extraHint = Option.when(askUser.isDefined)(AskUserMcpServer.Hint)
      )
      val mcpUrl = askUser.map(_.server.url)
      val args = sessions.dispatchFor(session) match
        case Dispatch.Resume(serverId) =>
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
      new CodexConversation(
        process,
        initialPrompt = displayPrompt,
        outputSchema = outputSchema,
        askUser = askUser,
        schemaFile = schemaFile,
        configuredModel = config.model
      )
    }

  /** Write the `--output-schema` payload (if any) to a unique temp file OUTSIDE
    * the working tree — never `workDir` — so it can't race a concurrent
    * structured call (the reviewer fan-out) or get swept into a flow's `git add
    * -A`. `deleteOnExit = false`: cleanup is explicit, via
    * [[SubprocessSpawn.deleteFileResource]] wired into both success and failure
    * paths, not left to JVM-exit best-effort.
    */
  private def writeSchemaIfPresent(schema: Option[String]): Option[os.Path] =
    schema.map: body =>
      os.temp(
        body,
        prefix = "orca-codex-schema-",
        suffix = ".json",
        deleteOnExit = false
      )
