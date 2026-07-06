package orca.tools.codex

import orca.events.OrcaListener
import orca.agents.{BackendTag, AgentConfig, SessionId, WireSessionId}
import orca.backend.{
  Conversation,
  Conversations,
  Dispatch,
  AgentBackend,
  AgentResult,
  SessionMode,
  SessionRegistry,
  SubprocessSpawn,
  SystemPromptComposer
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.CliRunner
import ox.{Ox, supervised}
import ox.channels.BufferCapacity

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
  * the same session id route through `codex exec resume <server-id>` via the
  * [[sessions]] registry (a [[SessionRegistry.ClientToServer]]).
  *
  * Interactive calls additionally stand up an `ask_user` MCP host bridge
  * ([[AskUserMcpServer]]) on an ephemeral port and register it with codex via
  * the top-level `-c mcp_servers.orca.url=…` config override, so the agent can
  * call `ask_user` to surface a clarifying question to the user. Autonomous
  * calls skip the bridge entirely.
  */
private[orca] class CodexBackend(
    cli: CliRunner,
    private[codex] val sessionsDir: os.Path = os.home / ".codex" / "sessions"
)(using BufferCapacity)
    extends AgentBackend[BackendTag.Codex.type]:

  /** Best-effort probe: walks [[sessionsDir]] looking for a file whose name
    * matches `rollout-*-<id>.jsonl`. Returns `false` — safe re-seed — when no
    * match is found, the sessions dir doesn't exist, or the id fails the
    * [[orca.agents.isSafeSessionId]] guard (blocks regex injection; e.g.
    * id=`.*` would match every file without the guard).
    *
    * Note: the installed codex on some machines uses SQLite
    * (`~/.codex/state_5.sqlite`) rather than `rollout-*.jsonl` files. If no
    * matching files exist, the probe returns `false` → re-seed, which is always
    * safe.
    */
  override def sessionExists(
      session: SessionId[BackendTag.Codex.type]
  ): Boolean =
    probeGuarded(SessionId.value(session)): id =>
      os.exists(sessionsDir) && os.walk
        .stream(sessionsDir)
        .exists(p =>
          p.last.startsWith("rollout-") && p.last.endsWith(s"-$id.jsonl")
        )

  /** Maps the client-allocated session id (the UUID the caller passes around)
    * to codex's server-allocated thread id (learned from `thread.started`).
    * `codex exec` mints its own id, so we keep this mapping so subsequent calls
    * dispatch through `codex exec resume <server-id>`.
    */
  private val sessions =
    new SessionRegistry.ClientToServer[BackendTag.Codex.type]

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Codex.type],
      config: AgentConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): AgentResult[BackendTag.Codex.type] =
    // Self-scoped: the conversation forks its workers into this per-call Ox, the
    // drain consumes them, and `cancel` (the `finally`) tears the subprocess +
    // forks down before the scope joins.
    supervised:
      val conv = openConversation(
        prompt = prompt,
        mode = SessionMode.Autonomous,
        session = session,
        config = config,
        workDir = workDir,
        // Forwarded so (a) `conv.outputSchema` signals structured mode to the
        // drain (suppressing the raw JSON payload from the user log) and (b)
        // `--output-schema` enforces the contract on the codex side too.
        // `exec resume` rejects `--output-schema`, so retries against an
        // existing session fall back to prompt-only enforcement; the
        // retry-with-corrective-prompt loop in `DefaultAgentCall` handles a
        // resume that produces malformed JSON.
        outputSchema = outputSchema
      )
      // drainAndCommit records the client→server mapping so a follow-up call on
      // this client id resumes the right thread; the result carries the server
      // thread id as its wireId, and the caller keeps using the client id.
      try Conversations.drainAndCommit("codex", conv, session, sessions, events)
      finally conv.cancel()

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Codex.type],
      displayPrompt: String,
      config: AgentConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.Codex.type] =
    openConversation(
      prompt,
      mode = SessionMode.Interactive(displayPrompt),
      session = session,
      config = config,
      workDir = workDir,
      outputSchema = outputSchema
    )

  /** Spawn `codex exec --json` (fresh) or `codex exec resume <server-id>`
    * (continuation), and wrap the process in a live [[CodexConversation]].
    * Stdin is closed immediately — codex consumes the prompt argv-side.
    *
    * The fresh-vs-resume decision is driven by [[sessions.dispatchFor]]: if
    * we've seen this client id before we resume against its mapped server
    * thread, otherwise we start fresh and the post-drain `commitSuccess` (via
    * [[registerSession]] on the interactive path) records the mapping.
    *
    * `Interactive` mode wires the MCP `ask_user` tool: stand up the bridge +
    * Netty server, hand the URL to `CodexArgs` for the `-c mcp_servers.orca`
    * override, fold the system-prompt hint into the user prompt (codex has no
    * `--append-system-prompt`), and hand the bridge + server to
    * `CodexConversation` so it can surface `UserQuestion` events and close the
    * binding on finalize. `Autonomous` skips all of it.
    *
    * If anything between resource allocation and conversation construction
    * throws we tear down the server so no Netty binding leaks.
    */
  private def openConversation(
      prompt: String,
      mode: SessionMode,
      session: SessionId[BackendTag.Codex.type],
      config: AgentConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.Codex.type] =
    val (askUser, displayPrompt): (Option[AskUserSession], String) =
      mode match
        case SessionMode.Interactive(p) => (Some(AskUserSession.allocate()), p)
        case SessionMode.Autonomous     => (None, "")
    SubprocessSpawn.open("codex", askUser.toList) {
      // codex `exec` has no `--system-prompt` flag (it picks up `AGENTS.md`
      // files for static instructions), so fold the composed system prompt into
      // the user prompt.
      val finalPrompt = SystemPromptComposer.foldIntoPrompt(
        config,
        prompt,
        extraHint = Option.when(askUser.isDefined)(AskUserMcpServer.Hint)
      )
      val schemaFile = writeSchemaIfPresent(outputSchema, workDir)
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
      // codex doesn't accept user turns over stdin once the initial prompt has
      // been argv-supplied; close immediately so the child stops waiting on
      // stdin EOF. Same reflex as claude's single-shot stream-json path.
      process.closeStdin()
      new CodexConversation(
        process,
        initialPrompt = displayPrompt,
        outputSchema = outputSchema,
        askUser = askUser
      )
    }

  /** Record the server-allocated thread id so subsequent calls with the same
    * client id resume that thread. Called by [[runAutonomous]] post-drain and
    * by [[orca.agents.DefaultAgentCall]] post-`interaction.drive` on the
    * interactive path; delegates to the registry's `commitSuccess`.
    */
  override def registerSession(
      client: SessionId[BackendTag.Codex.type],
      server: WireSessionId[BackendTag.Codex.type]
  ): Unit = sessions.commitSuccess(client, server)

  override def resumeWireId(
      client: SessionId[BackendTag.Codex.type]
  ): Option[WireSessionId[BackendTag.Codex.type]] =
    sessions.resumeWireId(client)

  private def writeSchemaIfPresent(
      schema: Option[String],
      workDir: os.Path
  ): Option[os.Path] =
    schema.map: body =>
      val file = workDir / ".codex" / "orca-output-schema.json"
      os.write.over(file, body, createFolders = true)
      file
