package orca.tools.claude

/** Recognises the `tool_result` claude sends back when it refused a tool call
  * for lack of permission, as opposed to a tool that ran and failed.
  */
private[claude] object PermissionRefusal:
  private val Refusal =
    """Claude requested permissions to use (\S+), but you haven't granted it yet\.?""".r

  /** The refused tool's name, if the whole of `content` is a refusal. Output
    * that merely quotes the phrase (a read of this file, a grep of a log) does
    * not match.
    */
  def toolName(content: String): Option[String] =
    content.trim match
      case Refusal(tool) => Some(tool)
      case _             => None
