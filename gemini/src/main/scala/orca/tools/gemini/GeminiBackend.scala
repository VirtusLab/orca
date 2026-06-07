package orca.tools.gemini

import orca.events.OrcaListener
import orca.llm.{BackendTag, LlmConfig, SessionId}
import orca.{AgentTurnFailed, OrcaFlowException}
import orca.backend.{
  Conversation,
  Conversations,
  Dispatch,
  LlmBackend,
  LlmResult,
  SessionMode,
  SessionRegistry,
  SystemPromptComposer
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.CliRunner
import ox.Ox
import ox.channels.BufferCapacity

/** Gemini backend. Both autonomous and interactive paths drive `gemini -p
  * <prompt> --output-format stream-json` over stdio: stdout JSONL is parsed
  * into [[orca.tools.gemini.jsonl.InboundEvent]]s, and the accumulated
  * assistant message content becomes the result at the terminal `result` event.
  * See [[../../../adr/0015-gemini-stream-json-driver.md ADR 0015]] for the
  * protocol shape and the rationale.
  *
  * Both modes wrap the subprocess in a [[GeminiConversation]]; the autonomous
  * path drains it internally via [[orca.backend.Conversations.drainAutonomous]]
  * while the interactive path returns the conversation for an `Interaction` to
  * drive. Multi-turn: subsequent calls with the same session id route through
  * `gemini --resume <session-id>` via the [[sessions]] registry (a
  * [[SessionRegistry.ClientToServer]]), where the id was learned from the
  * `init` event of the prior run.
  *
  * Interactive calls additionally stand up an `ask_user` MCP host bridge
  * ([[AskUserMcpServer]]) on an ephemeral port and register it with gemini by
  * merging an `mcpServers.orca` entry into a project-local
  * `.gemini/settings.json` ([[GeminiSettings]]) — gemini has no inline `-c` MCP
  * override like codex. The merge is restored when the conversation finalises
  * (the restore rides as an `extras` `AutoCloseable` on the
  * [[AskUserSession]]). Autonomous calls skip the bridge entirely.
  */
private[orca] class GeminiBackend(cli: CliRunner)(using Ox, BufferCapacity)
    extends LlmBackend[BackendTag.Gemini.type]:

  /** Maps the client-allocated session id to gemini's `init`-reported session
    * id. `gemini -p` mints its own id, so we keep this mapping to dispatch
    * subsequent calls through `gemini --resume <server-id>`.
    */
  private val sessions =
    new SessionRegistry.ClientToServer[BackendTag.Gemini.type]

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Gemini.type],
      config: LlmConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): LlmResult[BackendTag.Gemini.type] =
    val conv = openConversation(
      prompt = prompt,
      mode = SessionMode.Autonomous,
      session = session,
      config = config,
      workDir = workDir,
      // Forwarded so `conv.outputSchema` signals structured mode to the drain
      // (suppressing the raw JSON payload from the user log). gemini has no
      // `--output-schema` flag, so enforcement is prompt-only.
      outputSchema = outputSchema
    )
    try
      val result = Conversations.drainAutonomous(conv, events)
      sessions.commitSuccess(session, result.sessionId)
      // Hide the server-allocated id from the caller — they keep using the
      // client id they passed in. Future calls resolve via the registry.
      result.copy(sessionId = session)
    catch
      // Preserve the non-retryable type: a turn that ran and failed must not
      // be retried (it would reopen the now-registered session id).
      case e: AgentTurnFailed => throw e
      case e: OrcaFlowException =>
        throw new OrcaFlowException(s"gemini CLI failed: ${e.getMessage}")

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Gemini.type],
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.Gemini.type] =
    openConversation(
      prompt,
      mode = SessionMode.Interactive(displayPrompt),
      session = session,
      config = config,
      workDir = workDir,
      outputSchema = outputSchema
    )

  /** Spawn `gemini -p` (fresh) or `gemini --resume <server-id> -p`
    * (continuation), and wrap the process in a live [[GeminiConversation]].
    * Stdin is closed immediately — gemini consumes the prompt argv-side.
    *
    * `Interactive` mode wires the MCP `ask_user` tool: stand up the bridge +
    * Netty server, merge the server URL into `.gemini/settings.json` (the
    * restore rides as an `extras` `AutoCloseable`), fold the system-prompt hint
    * into the user prompt (gemini has no `--append-system-prompt`), and hand
    * the bundle to `GeminiConversation` so it can surface `UserQuestion` events
    * and restore the settings file on finalize. `Autonomous` skips all of it.
    */
  private def openConversation(
      prompt: String,
      mode: SessionMode,
      session: SessionId[BackendTag.Gemini.type],
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[BackendTag.Gemini.type] =
    val (askUser, displayPrompt): (Option[AskUserSession], String) =
      mode match
        case SessionMode.Interactive(p) =>
          // The settings.json merge is registered as an `extras` AutoCloseable
          // so it's restored when the conversation finalises (the base closes
          // the AskUserSession post-drain).
          val session = AskUserSession.allocate: server =>
            List(GeminiSettings.register(workDir, server.url))
          (Some(session), p)
        case SessionMode.Autonomous => (None, "")
    try
      val finalPrompt = mergeSystemPrompt(
        config,
        prompt,
        extraHint = Option.when(askUser.isDefined)(AskUserMcpServer.Hint)
      )
      val args = sessions.dispatchFor(session) match
        case Dispatch.Resume(serverId) =>
          GeminiArgs.resume(serverId, finalPrompt, config)
        case Dispatch.Fresh(_) =>
          GeminiArgs.headless(finalPrompt, config)
      val process = cli.spawnPiped(args, cwd = workDir, pipeStderr = true)
      try
        // gemini consumes the prompt argv-side and ignores stdin; close it so
        // the child stops waiting on stdin EOF.
        process.closeStdin()
        new GeminiConversation(
          process,
          initialPrompt = displayPrompt,
          outputSchema = outputSchema,
          askUser = askUser
        )
      catch
        case e: Exception =>
          process.sendSigInt()
          throw OrcaFlowException(
            s"Failed to open gemini session: ${e.getMessage}"
          )
    catch
      case e: Throwable =>
        // Any failure between resource allocation and a fully-constructed
        // GeminiConversation: tear down the MCP server (which also restores
        // the settings.json via its extras) so nothing leaks. Once the
        // conversation owns the resources they ride through `onFinalize`.
        askUser.foreach(_.close())
        throw e

  /** Record the server session id so subsequent calls with the same client id
    * resume that session. Called by [[runAutonomous]] post-drain and by
    * [[orca.llm.DefaultLlmCall]] post-`interaction.drive` on the interactive
    * path; delegates to the registry's `commitSuccess`.
    */
  override def registerSession(
      client: SessionId[BackendTag.Gemini.type],
      server: SessionId[BackendTag.Gemini.type]
  ): Unit = sessions.commitSuccess(client, server)

  /** gemini has no `--append-system-prompt` flag (it picks up `GEMINI.md` files
    * in the working directory for static instructions). Fold the composed
    * system prompt (config + optional extra hint) into the user prompt as a
    * preamble — a low-tech but predictable substitute, identical to codex's
    * approach.
    */
  private def mergeSystemPrompt(
      config: LlmConfig,
      userPrompt: String,
      extraHint: Option[String]
  ): String =
    SystemPromptComposer.combine(config, extraHint) match
      case None => userPrompt
      case Some(text) =>
        s"""System guidance:
           |$text
           |
           |User request:
           |$userPrompt""".stripMargin
