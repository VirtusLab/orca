package orca.tools.pi

import orca.backend.CliArgs
import orca.agents.{
  AgentConfig,
  AutoApprove,
  Enforcement,
  EnforcementCell,
  ToolSet,
  TurnDispatch
}

/** Maps Orca backend configuration to Pi CLI arguments; the argv carries only
  * process/session/configuration flags, since prompts go over stdin.
  *
  * Session continuity uses Pi's on-disk sessions: `--session-dir` points at a
  * directory Pi seeds a fresh session in; `resume` adds `--continue` to pick up
  * the prior session there. (`--session <id>` only resumes an existing id, so
  * it can't seed a caller-chosen id for a new session.)
  */
private[pi] object PiArgs:

  val ReadOnlyTools: Seq[String] = Seq("read", "grep", "find", "ls")

  /** Pi has no web/fetch tool, so the only network path is the general `bash`
    * tool — which also permits writes. Added on [[ToolSet.NetworkOnly]] turns;
    * see [[enforcementCell]] for what that costs the tier's guarantee.
    */
  val NetworkTool: String = "bash"

  def rpc(
      sessionDir: os.Path,
      resume: Boolean,
      config: AgentConfig,
      systemPromptFile: Option[os.Path],
      askUserExtension: Option[os.Path] = None
  ): Seq[String] =
    Seq("pi", "--mode", "rpc", "--session-dir", sessionDir.toString) ++
      Option.when(resume)("--continue").toSeq ++
      CliArgs.modelArgs(config) ++
      systemPromptArgs(systemPromptFile) ++
      toolsWiring(
        config.tools,
        config.autoApprove,
        askUserExtension.isDefined
      ).args ++
      extensionArgs(askUserExtension)

  private def systemPromptArgs(file: Option[os.Path]): Seq[String] =
    CliArgs.flag("--append-system-prompt", file)(_.toString)

  private case class ToolsWiring(args: Seq[String], cell: EnforcementCell)

  /** Maps [[AgentConfig.tools]] to pi's `--tools` allowlist (`Full` omits the
    * flag for all built-ins), and classifies how strongly it holds. The
    * ask-user extension tool is appended when present; it widens what is on
    * offer without changing the tier's guarantee, so the cell ignores it.
    */
  private def toolsWiring(
      tools: ToolSet,
      autoApprove: AutoApprove,
      includeAskUser: Boolean
  ): ToolsWiring =
    tools match
      case ToolSet.ReadOnly =>
        ToolsWiring(
          toolsFlag(ReadOnlyTools, includeAskUser),
          EnforcementCell(
            Enforcement.Hard,
            "the `--tools` allowlist excludes every writable tool"
          )
        )
      case ToolSet.NetworkOnly =>
        ToolsWiring(
          toolsFlag(ReadOnlyTools :+ NetworkTool, includeAskUser),
          EnforcementCell(
            Enforcement.PromptOnly,
            "the allowlist has to include `bash` to reach the network, and `bash` also writes, so only the prompt withholds edits"
          )
        )
      case ToolSet.Full =>
        autoApprove match
          case AutoApprove.All | AutoApprove.Only(_) =>
            ToolsWiring(
              Seq.empty,
              EnforcementCell(
                Enforcement.Ignored,
                "pi RPC never prompts, and the argv encodes no approval policy"
              )
            )

  private def toolsFlag(
      tools: Seq[String],
      includeAskUser: Boolean
  ): Seq[String] =
    val all =
      if includeAskUser then tools :+ PiAskUserExtension.ToolName else tools
    Seq("--tools", all.mkString(","))

  private def extensionArgs(file: Option[os.Path]): Seq[String] =
    CliArgs.flag("--extension", file)(_.toString)

  /** How strongly pi enforces each `(tools, autoApprove)` combination — see
    * [[toolsWiring]], which derives this from the same match that builds the
    * flags. `includeAskUser` is `false` here for the same reason the wiring's
    * cell ignores it.
    */
  def enforcementCell(
      tools: ToolSet,
      autoApprove: AutoApprove,
      dispatch: TurnDispatch
  ): EnforcementCell = dispatch match
    // Same either way: [[rpc]] emits the `--tools` flag alongside `--continue`.
    // Matched rather than ignored so a new dispatch has to answer here.
    case TurnDispatch.Fresh | TurnDispatch.Resumed =>
      toolsWiring(tools, autoApprove, includeAskUser = false).cell
