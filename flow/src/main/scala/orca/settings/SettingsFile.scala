package orca.settings

import orca.StackSettings

/** Strict line format for `.orca/settings.properties`: `#` comments, `key =
  * value` with the value taken verbatim (trimmed) after the first `=`, repeated
  * keys append in file order, an empty value is equivalent to omitting the key.
  * Hand-rolled rather than `java.util.Properties`, whose backslash/unicode
  * escape handling would mangle shell commands (ADR 0019).
  */
object SettingsFile:
  val ValidKeys: Set[String] = Set("format", "lint", "test")

  /** Left = human-readable problem naming the offending line and the valid keys
    * (the lifecycle aborts with it before any tree mutation).
    */
  def parse(content: String): Either[String, StackSettings] =
    content.linesIterator.zipWithIndex.foldLeft(
      Right(StackSettings.empty): Either[String, StackSettings]
    ):
      case (problem @ Left(_), _)      => problem
      case (Right(acc), (line, index)) => parseLine(acc, line, index + 1)

  private def parseLine(
      acc: StackSettings,
      line: String,
      number: Int
  ): Either[String, StackSettings] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then Right(acc)
    else
      trimmed.indexOf('=') match
        case -1 =>
          Left(
            s"line $number: `$trimmed` is not a `#` comment and has no `=` " +
              "— expected `key = value`"
          )
        case eq =>
          val key = trimmed.take(eq).trim
          // Everything after the FIRST `=` belongs to the value, so commands
          // containing `=` (e.g. `FOO=bar cargo check`) survive intact. Keys
          // are case-sensitive.
          val value = trimmed.drop(eq + 1).trim
          if !ValidKeys(key) then
            Left(
              s"line $number: unknown key `$key` — valid keys: " +
                ValidKeys.toList.sorted.mkString(", ")
            )
          else if value.isEmpty then Right(acc)
          else Right(append(acc, key, value))

  private def append(
      acc: StackSettings,
      key: String,
      command: String
  ): StackSettings =
    key match
      case "format" => acc.copy(format = acc.format :+ command)
      case "lint"   => acc.copy(lint = acc.lint :+ command)
      case "test"   => acc.copy(test = acc.test :+ command)
