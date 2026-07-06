package orca.tools.gemini

import orca.events.OrcaListener
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  SessionId,
  ToolSet
}
import orca.subprocess.CliResult
import orca.backend.{
  Conversation,
  Conversations,
  Dispatch,
  AgentBackend,
  AgentResult,
  SessionMode,
  SessionRegistry,
  SessionSupport,
  SubprocessSpawn,
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
  * `gemini --resume <session-id>` via the [[registry]] (a
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
private[orca] class GeminiBackend(cli: CliRunner)(using BufferCapacity)
    extends AgentBackend[BackendTag.Gemini.type]:

  /** Maps the client-allocated session id to gemini's `init`-reported session
    * id. `gemini -p` mints its own id, so we keep this mapping to dispatch
    * subsequent calls through `gemini --resume <server-id>`.
    */
  private val registry =
    new SessionRegistry.ClientToServer[BackendTag.Gemini.type]

  /** Gemini's sessions are server-side and durable, so it is
    * [[SessionSupport.Durable]]: the client→server map is persisted to the
    * progress log and rehydrated on resume. Existence probes the SERVER id the
    * registry resolves for a client (gemini mints its own id; the caller's
    * stable id never appears in `--list-sessions`): it runs `gemini
    * --list-sessions` and scans the output for that server id (substring).
    *
    * Because [[SessionSupport.exists]] is registry-gated, `false` results when
    * no server id is mapped (the map hasn't been rehydrated ⇒ no known live
    * session), on non-zero exit, any exception, or if the server id fails the
    * [[orca.agents.isSafeSessionId]] guard (kept for uniformity with the other
    * probes; the substring scan is not injection-susceptible).
    */
  val tag: BackendTag.Gemini.type = BackendTag.Gemini

  def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
    GeminiArgs.enforcement(tools, autoApprove)

  val sessions: SessionSupport[BackendTag.Gemini.type] =
    SessionSupport.Durable(
      registry,
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
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): AgentResult[BackendTag.Gemini.type] =
    // drainAndCommit records the client→server mapping so a follow-up call on
    // this client id resumes the right thread; the result carries the server
    // thread id as its wireId, and the caller keeps using the client id.
    Conversations.runAutonomous("gemini", session, registry, events):
      openConversation(
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

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Gemini.type],
      displayPrompt: String,
      config: AgentConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.Gemini.type] =
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
      config: AgentConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  )(using Ox): Conversation[BackendTag.Gemini.type] =
    val (askUser, displayPrompt): (Option[AskUserSession], String) =
      mode match
        case SessionMode.Interactive(p) =>
          val askUserSession = AskUserSession.allocate: server =>
            List(GeminiSettings.register(workDir, server.url))
          (Some(askUserSession), p)
        case SessionMode.Autonomous => (None, "")
    // On a spawn/build failure the ask_user bundle is closed, which also
    // restores the settings.json via its `extras`, so nothing leaks.
    SubprocessSpawn.open("gemini", askUser.toList) {
      // gemini has no `--append-system-prompt` flag (it picks up `GEMINI.md`
      // files for static instructions), so fold the composed system prompt into
      // the user prompt — same approach as codex.
      val finalPrompt = SystemPromptComposer.foldIntoPrompt(
        config,
        prompt,
        extraHint = Option.when(askUser.isDefined)(AskUserMcpServer.Hint)
      )
      val args = registry.dispatchFor(session) match
        case Dispatch.Resume(serverId) =>
          GeminiArgs.resume(serverId, finalPrompt, config)
        case Dispatch.Fresh(_) =>
          GeminiArgs.headless(finalPrompt, config)
      cli.spawnPiped(args, cwd = workDir, pipeStderr = true)
    } { process =>
      // Close stdin so the child stops waiting on EOF (gemini reads the prompt
      // argv-side).
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
