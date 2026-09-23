package orca.tools.gemini

import orca.agents.{
  Model,
  AutoApprove,
  BackendTag,
  EnforcementCell,
  StructuredOutputMode,
  ToolSet,
  TurnDispatch
}
import orca.subprocess.CliResult
import orca.backend.{
  AskUserChannel,
  Conversation,
  TurnRequest,
  Dispatch,
  AgentBackend,
  IdScheme,
  SessionSupport,
  SubprocessSpawn,
  SystemPromptComposer,
  TurnResources
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.CliRunner
import ox.{Ox, discard}

/** Gemini backend. Both autonomous and interactive paths drive `gemini -p
  * <prompt> --output-format stream-json` over stdio: stdout JSONL is parsed
  * into [[orca.tools.gemini.jsonl.InboundEvent]]s, and the accumulated
  * assistant message content becomes the result at the terminal `result` event.
  * See [[../../../adr/0015-gemini-stream-json-driver.md ADR 0015]] for the
  * protocol shape and rationale.
  *
  * Both modes wrap the subprocess in a [[GeminiConversation]]. Multi-turn calls
  * with the same session id route through `gemini --resume <session-id>` via
  * [[sessions]] (an [[IdScheme.ServerMinted]] id learned from the prior run's
  * `init` event).
  *
  * Interactive calls additionally stand up an `ask_user` MCP host bridge
  * ([[AskUserMcpServer]]) and register it by merging an `mcpServers.orca` entry
  * into a project-local `.gemini/settings.json` ([[GeminiSettings]]) — gemini
  * has no inline `-c` MCP override. The merge is restored when the turn ends.
  * Autonomous calls skip the bridge.
  */
private[orca] class GeminiBackend(
    cli: CliRunner,
    /** Fixed at construction; every spawn runs in this directory. The `os.pwd`
      * default serves bare/test construction; the runtime passes the flow's
      * real `workDir`.
      */
    override val workDir: os.Path = os.pwd
) extends AgentBackend[BackendTag.Gemini.type]:

  /** Gemini's sessions are server-side and durable: the client→server map is
    * persisted in the session records (`.orca/cache/runs/<key>.sessions.json`)
    * and rehydrated on resume. The existence probe runs `gemini
    * --list-sessions` and scans for the resolved SERVER id (substring) — gemini
    * mints its own id; the caller's stable id never appears there.
    * [[SessionSupport.dispatchFor]] answers `Fresh` when no server id is mapped
    * (including an id rejected by the [[orca.agents.SessionId.isSafe]] guard),
    * or when the probe exits non-zero or throws.
    */
  val tag: BackendTag.Gemini.type = BackendTag.Gemini

  export GeminiArgs.enforcementCell

  /** The gemini CLI has no output-schema flag (see [[runAutonomous]]) —
    * enforcement is prompt-only and the reply text is the JSON value.
    */
  override def structuredOutputMode: StructuredOutputMode =
    StructuredOutputMode.RawText

  def cheapModel(leading: Option[Model]): Option[Model] =
    Some(GeminiModels.Flash)

  /** The sole session handle. [[IdScheme.ServerMinted]]: the client-allocated
    * id maps to gemini's `init`-reported session id, so subsequent calls
    * dispatch through `gemini --resume <server-id>`.
    */
  val sessions: SessionSupport[BackendTag.Gemini.type] =
    SessionSupport.durable(
      IdScheme.ServerMinted,
      id =>
        val result = listSessionsOutput()
        result.exitCode == 0 && result.stdout.linesIterator.exists(
          _.contains(id)
        )
    )

  /** Spawn `gemini -p` (fresh) or `gemini --resume <server-id> -p`
    * (continuation) and wrap the process in a live [[GeminiConversation]].
    * Stdin is closed immediately — gemini consumes the prompt argv-side.
    *
    * `Interactive` mode additionally wires the MCP `ask_user` tool: stand up
    * the bridge, merge the server URL into `.gemini/settings.json` (restored
    * when the turn scope ends), and fold the system-prompt hint into the user
    * prompt. `Autonomous` skips all of it.
    */
  override protected[orca] def open(
      turn: TurnRequest[BackendTag.Gemini.type]
  )(using Ox): Conversation[BackendTag.Gemini.type] =
    import turn.*
    val askUser: Option[AskUserSession] =
      Option.when(mode.isInteractive):
        val session = AskUserSession.allocate()
        TurnResources
          .useCloseable(GeminiSettings.register(workDir, session.server.url))
          .discard
        session
    SubprocessSpawn.open("gemini", events) {
      // gemini has no `--append-system-prompt` flag, so fold the composed
      // system prompt into the user prompt.
      val finalPrompt = SystemPromptComposer.foldIntoPrompt(
        config,
        prompt,
        extraHint = Option.when(askUser.isDefined)(AskUserMcpServer.Hint)
      )
      val args = dispatch match
        case Dispatch.Resume(serverId, _) =>
          GeminiArgs.resume(serverId, finalPrompt, config)
        case Dispatch.Fresh(_) =>
          GeminiArgs.headless(finalPrompt, config)
      cli.spawnPiped(args, cwd = workDir, pipeStderr = true)
    } { process =>
      // Close stdin so the child stops waiting on EOF.
      process.closeStdin()
      GeminiConversation(
        process,
        openingPrompt = mode.openingPrompt,
        outputSchema = outputSchema,
        askUser =
          askUser.fold(AskUserChannel.Unavailable)(AskUserChannel.Mcp(_))
      )
    }

  /** `gemini --list-sessions` in [[workDir]]: gemini keeps sessions per project
    * directory, so it lists only those the spawns in [[workDir]] can resume.
    */
  private[gemini] def listSessionsOutput(): CliResult =
    cli.run(Seq("gemini", "--list-sessions"), cwd = workDir)
