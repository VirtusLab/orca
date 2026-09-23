package orca.backend

/** Recognises a tool call the harness refused for lack of permission, as
  * opposed to a tool that ran and failed.
  *
  * Where a denial is observable in an autonomous turn, per backend:
  *   - claude: a tool result reading `Claude requested permissions to use
  *     <tool>, but you haven't granted it yet.` — matched by
  *     [[fromToolResult]]. A `can_use_tool` request for a tool outside the
  *     auto-approve set arrives as [[ConversationEvent.ApproveTool]] instead.
  *   - opencode: `permission.asked` becomes [[ConversationEvent.ApproveTool]],
  *     which the autonomous drain denies.
  *   - codex: a sandbox block is the command's own failure output,
  *     indistinguishable from a failing command; not detected.
  *   - gemini: a plan-mode refusal is an error tool result with no refusal
  *     phrase verified yet; not detected. To pin one, probe with `gemini
  *     --skip-trust --approval-mode plan --output-format stream-json -p "create
  *     a file x"`.
  *   - pi: `--tools` removes unlisted tools from the model's set, so there is
  *     nothing to deny.
  *
  * Interactive turns are not covered: a human is there to approve, so a denial
  * is their own decision.
  */
private[orca] object ToolDenial:
  private val ClaudeRefusal =
    """Claude requested permissions to use (\S+), but you haven't granted it yet\.?""".r

  /** The denied tool's name, if the whole of `content` is a permission refusal.
    * Output that merely quotes the phrase (a read of this file, a grep of a
    * log) does not match.
    */
  def fromToolResult(content: String): Option[String] =
    content.trim match
      case ClaudeRefusal(tool) => Some(tool)
      case _                   => None
