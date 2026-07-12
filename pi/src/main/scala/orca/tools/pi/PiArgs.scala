package orca.tools.pi

import orca.backend.CliArgs
import orca.agents.{AgentConfig, AutoApprove, Enforcement, ToolSet}

/** Maps Orca backend configuration to Pi CLI arguments. The backend drives Pi
  * through RPC mode and sends prompts over stdin, so the argv carries only
  * process/session/configuration flags.
  *
  * Session continuity uses Pi's on-disk sessions: `--session-dir` points at a
  * directory Pi creates a fresh session in on the first turn; `resume` adds
  * `--continue` so a later turn picks up the prior session in that dir. (Pi's
  * `--session <id>` only *resumes* an existing id, so it can't seed a
  * caller-chosen id for a new session.)
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

  /** Maps [[AgentConfig.tools]] to pi's `--tools` allowlist. `Full` omits the
    * flag (all built-in tools enabled); `ReadOnly` restricts to
    * [[ReadOnlyTools]]; `NetworkOnly` adds [[NetworkTool]] (`bash`) for network
    * access. The ask-user extension tool is appended when present.
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
    * [[toolsArgs]] for the `--tools` allowlist this classifies.
    *
    *   - `ReadOnly` → `Hard`: `--tools read,grep,find,ls` mechanically excludes
    *     every writable tool.
    *   - `NetworkOnly` → `PromptOnly`: pi has no web tool, so network arrives
    *     only via the general `bash` tool, which also permits writes — the
    *     no-edit guarantee then rests only on the planner prompt.
    *   - `Full` + `AutoApprove.All` / `Only(_)` → `Ignored`: pi RPC never
    *     prompts and the argv encodes no approval policy, so `autoApprove` is
    *     not represented at all.
    */
  def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
    tools match
      case ToolSet.ReadOnly    => Enforcement.Hard
      case ToolSet.NetworkOnly => Enforcement.PromptOnly
      case ToolSet.Full =>
        autoApprove match
          case AutoApprove.All | AutoApprove.Only(_) => Enforcement.Ignored
