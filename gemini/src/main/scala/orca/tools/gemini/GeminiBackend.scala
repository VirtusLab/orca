package orca.tools.gemini

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
import orca.subprocess.CliResult
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

/** Gemini backend. Both autonomous and interactive paths drive `gemini -p
  * <prompt> --output-format stream-json` over stdio: stdout JSONL is parsed
  * into [[orca.tools.gemini.jsonl.InboundEvent]]s, and the accumulated
  * assistant message content becomes the result at the terminal `result` event.
  * See [[../../../adr/0015-gemini-stream-json-driver.md ADR 0015]] for the
  * protocol shape and rationale.
  *
  * Both modes wrap the subprocess in a [[GeminiConversation]]; the autonomous
  * path drains it internally, the interactive path returns it for an
  * `Interaction` to drive. Multi-turn calls with the same session id route
  * through `gemini --resume <session-id>` via [[sessions]] (an
  * [[IdScheme.ServerMinted]] id learned from the prior run's `init` event).
  *
  * Interactive calls additionally stand up an `ask_user` MCP host bridge
  * ([[AskUserMcpServer]]) and register it by merging an `mcpServers.orca` entry
  * into a project-local `.gemini/settings.json` ([[GeminiSettings]]) — gemini
  * has no inline `-c` MCP override. The merge is restored when the conversation
  * finalises. Autonomous calls skip the bridge.
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
    * persisted to the progress log and rehydrated on resume. The existence
    * probe runs `gemini --list-sessions` and scans for the resolved SERVER id
    * (substring) — gemini mints its own id; the caller's stable id never
    * appears there. [[SessionSupport.willContinue]] resolves the mapping first,
    * so it returns `false` when no server id is mapped (including an id
    * rejected by the [[orca.agents.SessionId.isSafe]] guard at commit time), on
    * non-zero exit, or on any exception.
    */
  val tag: BackendTag.Gemini.type = BackendTag.Gemini

  override def enforcement(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): Enforcement =
    GeminiArgs.enforcement(tools, autoApprove)

  /** The gemini CLI has no output-schema flag (see [[runAutonomous]]) —
    * enforcement is prompt-only and the reply text is the JSON value.
    */
  override def structuredOutputMode: StructuredOutputMode =
    StructuredOutputMode.RawText

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

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Gemini.type],
      config: AgentConfig,
      events: OrcaListener,
      outputSchema: Option[String]
  ): AgentResult[BackendTag.Gemini.type] =
    // drainAndCommit records the client→server mapping so a follow-up call on
    // this client id resumes the right thread; the result carries the server
    // thread id as its wireId, and the caller keeps using the client id.
    Conversations.runAutonomous(session, sessions, events):
      openConversation(
        prompt = prompt,
        mode = ConversationMode.Autonomous,
        session = session,
        config = config,
        // Forwarded so `conv.outputSchema` signals structured mode to the drain
        // (suppressing the raw JSON payload from the user log).
        outputSchema = outputSchema
      )

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Gemini.type],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.Gemini.type] =
    openConversation(
      prompt,
      mode = ConversationMode.Interactive(displayPrompt),
      session = session,
      config = config,
      outputSchema = outputSchema
    )

  /** Spawn `gemini -p` (fresh) or `gemini --resume <server-id> -p`
    * (continuation) and wrap the process in a live [[GeminiConversation]].
    * Stdin is closed immediately — gemini consumes the prompt argv-side.
    *
    * `Interactive` mode additionally wires the MCP `ask_user` tool: stand up
    * the bridge, merge the server URL into `.gemini/settings.json`, and fold
    * the system-prompt hint into the user prompt. `Autonomous` skips all of it.
    */
  private def openConversation(
      prompt: String,
      mode: ConversationMode,
      session: SessionId[BackendTag.Gemini.type],
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.Gemini.type] =
    val displayPrompt = mode.displayPrompt
    val askUser: Option[AskUserSession] =
      Option.when(mode.isInteractive):
        AskUserSession.allocate: server =>
          List(GeminiSettings.register(workDir, server.url))
    // On a spawn/build failure the ask_user bundle is closed, which also
    // restores the settings.json via its `extras`, so nothing leaks.
    SubprocessSpawn.open("gemini", askUser.toList) {
      // gemini has no `--append-system-prompt` flag, so fold the composed
      // system prompt into the user prompt.
      val finalPrompt = SystemPromptComposer.foldIntoPrompt(
        config,
        prompt,
        extraHint = Option.when(askUser.isDefined)(AskUserMcpServer.Hint)
      )
      val args = sessions.dispatchFor(session) match
        case Dispatch.Resume(serverId) =>
          GeminiArgs.resume(serverId, finalPrompt, config)
        case Dispatch.Fresh(_) =>
          GeminiArgs.headless(finalPrompt, config)
      cli.spawnPiped(args, cwd = workDir, pipeStderr = true)
    } { process =>
      // Close stdin so the child stops waiting on EOF.
      process.closeStdin()
      new GeminiConversation(
        process,
        initialPrompt = displayPrompt,
        outputSchema = outputSchema,
        askUser = askUser
      )
    }

  /** Overridable in tests via a stub `CliRunner`; default runs `gemini
    * --list-sessions`.
    */
  private[gemini] def listSessionsOutput(): CliResult =
    cli.run(Seq("gemini", "--list-sessions"))
