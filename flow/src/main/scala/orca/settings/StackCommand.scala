package orca.settings

import orca.util.TextUtil

/** One `format`/`lint`/`test` command as it appears on a settings line:
  * single-line, trimmed, non-blank, not starting with `#`, and not the reserved
  * value `off`.
  */
private[orca] opaque type StackCommand = String

private[orca] object StackCommand:

  /** The one stack-key value with special meaning: explicitly disables that
    * gate. A project that genuinely has a command named `off` is not a concern
    * (ADR 0019 amendment).
    */
  private[settings] val Off: String = "off"

  /** Why a raw value is not a [[StackCommand]]. */
  enum Invalid(val message: String):
    case Blank extends Invalid("empty command")
    case CommentedOut
        extends Invalid(
          "starts with `#`, so `bash -c` runs nothing and exits 0"
        )
    case Disable extends Invalid(s"`$Off` disables the gate, not a command")

  /** `raw` with newlines collapsed and trimmed, if that is a valid command. */
  def from(raw: String): Either[Invalid, StackCommand] =
    val line = TextUtil.collapseNewlines(raw).trim
    if line.isEmpty then Left(Invalid.Blank)
    else if line.startsWith("#") then Left(Invalid.CommentedOut)
    else if line == Off then Left(Invalid.Disable)
    else Right(line)

  extension (command: StackCommand) def value: String = command
