package orca.backend.cursor

import orca.agents.{
  Model,
  AutoApprove,
  BackendTag,
  SessionId,
  StructuredOutputMode,
  ToolSet,
  TurnDispatch
}
import orca.backend.{
  AskUserChannel,
  LiveTurn,
  TurnRequest,
  Dispatch,
  AgentBackend,
  IdScheme,
  SessionSupport,
  SubprocessSpawn,
  SystemPromptComposer
}
import orca.subprocess.CliRunner
import ox.Ox

/** Cursor backend via `cursor-agent -p --output-format stream-json`. */
private[orca] class CursorBackend(
    cli: CliRunner,
    override val workDir: os.Path = os.pwd
) extends AgentBackend[BackendTag.Cursor.type]:

  val tag: BackendTag.Cursor.type = BackendTag.Cursor

  export CursorArgs.enforcementCell

  override def structuredOutputMode: StructuredOutputMode =
    StructuredOutputMode.RawText

  def cheapModel(leading: Option[Model]): Option[Model] =
    leading.orElse(Some(Model("composer-2.5")))

  /** Server-minted session ids from the init event; resume via `--resume`. The
    * agent exposes no list-sessions command, so the probe only checks id shape.
    */
  val sessions: SessionSupport[BackendTag.Cursor.type] =
    SessionSupport.durable(
      IdScheme.ServerMinted,
      id => SessionId.isSafe(id)
    )

  override protected[orca] def open(
      turn: TurnRequest[BackendTag.Cursor.type]
  )(using Ox): LiveTurn[BackendTag.Cursor.type] =
    import turn.*
    val finalPrompt = SystemPromptComposer.foldIntoPrompt(config, prompt)
    SubprocessSpawn.open("cursor-agent", events) {
      val args = dispatch match
        case Dispatch.Resume(serverId, _) =>
          CursorArgs.resume(serverId, finalPrompt, config)
        case Dispatch.Fresh(_) => CursorArgs.headless(finalPrompt, config)
      cli.spawnPiped(args, cwd = workDir)
    } { process =>
      process.closeStdin()
      CursorTurn(
        process,
        openingPrompt = mode.openingPrompt,
        outputSchema = outputSchema,
        askUser = AskUserChannel.Unavailable
      )
    }

private[orca] object CursorBackend:
  private[orca] def create(cli: CliRunner, workDir: os.Path): CursorBackend =
    new CursorBackend(cli, workDir)