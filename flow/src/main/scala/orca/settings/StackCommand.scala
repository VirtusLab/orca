package orca.settings

import orca.util.TextUtil

/** One `format`/`lint`/`test` command as it appears on a settings line:
  * single-line, trimmed, non-blank, not starting with `#`, and not `off`. Only
  * [[StackValue.parse]] creates one.
  */
private[orca] opaque type StackCommand = String

private[orca] object StackCommand:
  extension (command: StackCommand) def value: String = command

private def validated(line: String): StackCommand = line

/** A stack key's value, as written after `key =`. */
private[orca] enum StackValue:
  case Run(command: StackCommand)

  /** `off`: the gate is explicitly disabled, but the key counts as configured.
    * A project that genuinely has a command named `off` is not a concern (ADR
    * 0019 amendment 2026-07-26).
    */
  case Off

  /** Equivalent to omitting the key. */
  case Empty

  /** Starts with `#`: under `bash -c` that runs nothing and exits 0. */
  case CommentedOut

private[orca] object StackValue:
  private[settings] val OffLiteral: String = "off"

  /** Classifies `raw` after collapsing newlines and trimming. */
  def parse(raw: String): StackValue =
    val line = TextUtil.collapseNewlines(raw).trim
    if line.isEmpty then Empty
    else if line.startsWith("#") then CommentedOut
    else if line == OffLiteral then Off
    else Run(validated(line))
