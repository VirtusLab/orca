package orca.tools.pi

import orca.backend.CliArgs
import orca.agents.{AgentConfig, AutoApprove, Enforcement, ToolSet}

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
    * the no-edit guarantee is then prompt-only (the planner prompts forbid
    * edits), not enforced by the allowlist.
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

  /** How strongly pi enforces each `(tools, autoApprove)` combination:
    *   - `ReadOnly` → `Hard`: the `--tools` allowlist mechanically excludes
    *     every writable tool.
    *   - `NetworkOnly` → `PromptOnly`: network arrives only via the general
    *     `bash` tool, which also permits writes, so the no-edit guarantee rests
    *     on the planner prompt.
    *   - `Full` + `AutoApprove.All` / `Only(_)` → `Ignored`: pi RPC never
    *     prompts and the argv encodes no approval policy.
    */
  def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
    tools match
      case ToolSet.ReadOnly    => Enforcement.Hard
      case ToolSet.NetworkOnly => Enforcement.PromptOnly
      case ToolSet.Full =>
        autoApprove match
          case AutoApprove.All | AutoApprove.Only(_) => Enforcement.Ignored
