package orca.tools.pi

import orca.backend.CliArgs
import orca.agents.{
  AgentConfig,
  AutoApprove,
  Enforcement,
  EnforcementCell,
  ToolSet
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
      toolsArgs(config, askUserExtension.isDefined) ++
      extensionArgs(askUserExtension)

  private def systemPromptArgs(file: Option[os.Path]): Seq[String] =
    CliArgs.flag("--append-system-prompt", file)(_.toString)

  /** Maps [[AgentConfig.tools]] to pi's `--tools` allowlist (`Full` omits the
    * flag for all built-ins); the ask-user extension tool is appended when
    * present.
    */
  private def toolsArgs(
      config: AgentConfig,
      includeAskUser: Boolean
  ): Seq[String] =
    config.tools match
      case ToolSet.Full     => Seq.empty
      case ToolSet.ReadOnly => toolsFlag(ReadOnlyTools, includeAskUser)
      case ToolSet.NetworkOnly =>
        toolsFlag(ReadOnlyTools :+ NetworkTool, includeAskUser)

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
    * [[toolsArgs]] for the flags this classifies.
    */
  def enforcementCell(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): EnforcementCell =
    tools match
      case ToolSet.ReadOnly =>
        EnforcementCell(
          Enforcement.Hard,
          "the `--tools` allowlist excludes every writable tool"
        )
      case ToolSet.NetworkOnly =>
        EnforcementCell(
          Enforcement.PromptOnly,
          "the allowlist has to include `bash` to reach the network, and `bash` also writes, so only the prompt withholds edits"
        )
      case ToolSet.Full =>
        autoApprove match
          case AutoApprove.All | AutoApprove.Only(_) =>
            EnforcementCell(
              Enforcement.Ignored,
              "pi RPC never prompts, and the argv encodes no approval policy"
            )
