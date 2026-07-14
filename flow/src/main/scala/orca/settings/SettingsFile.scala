package orca.settings

import orca.StackSettings

/** One line of a rendered settings file — what auto-discovery hands to
  * [[SettingsFile.render]] (ADR 0019).
  */
enum SettingsEntry:
  /** `key = command`, with an optional trailing `# comment` carrying the
    * discovery evidence.
    */
  case Command(key: String, command: String, comment: Option[String])

  /** Rendered commented-out — `# key = (reason)` — so the task stays unset and
    * the line is invisible to the parser.
    */
  case Unset(key: String, reason: String)

/** Strict line format for `.orca/settings.properties`: `#` comments, `key =
  * value` with the value taken verbatim (trimmed) after the first `=` — except
  * for a trailing comment at the exact [[SettingsFile.render]] separator (see
  * `CommentSeparator`) — repeated keys append in file order, an empty value is
  * equivalent to omitting the key. Hand-rolled rather than
  * `java.util.Properties`, whose backslash/unicode escape handling would mangle
  * shell commands (ADR 0019).
  */
object SettingsFile:
  val ValidKeys: Set[String] = Set("format", "lint", "test")

  /** The exact separator [[render]] places between a command and its evidence
    * comment. [[parse]] strips a trailing comment only at this separator — a
    * bare `#` elsewhere in the value is part of the command (`bash -c` handles
    * quoting) — so rendered files round-trip to the plain commands.
    */
  private val CommentSeparator = "    # "

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
          val value = stripInlineComment(trimmed.drop(eq + 1)).trim
          if !ValidKeys(key) then
            Left(
              s"line $number: unknown key `$key` — valid keys: " +
                ValidKeys.toList.sorted.mkString(", ")
            )
          else if value.isEmpty then Right(acc)
          else Right(append(acc, key, value))

  private def stripInlineComment(value: String): String =
    value.indexOf(CommentSeparator) match
      case -1 => value
      case at => value.take(at)

  /** The header comment lines [[render]] places at the top of every settings
    * file.
    */
  val Header: String =
    "# orca stack settings — edit freely, commit with the project.\n" +
      "# Delete this file to re-run auto-discovery."

  /** The full settings-file text for `entries`, one line each under [[Header]],
    * newline-terminated.
    */
  def render(entries: List[SettingsEntry]): String =
    (Header :: entries.map(renderEntry)).mkString("", "\n", "\n")

  private def renderEntry(entry: SettingsEntry): String =
    entry match
      case SettingsEntry.Command(key, command, comment) =>
        // A command containing `#` loses its comment: `parse` strips at the
        // first CommentSeparator, and only a `#`-free command guarantees the
        // separator it finds is the one appended here.
        comment match
          case Some(text) if !command.contains('#') =>
            s"$key = $command$CommentSeparator$text"
          case _ => s"$key = $command"
      case SettingsEntry.Unset(key, reason) =>
        s"# $key =   ($reason)"

  private def append(
      acc: StackSettings,
      key: String,
      command: String
  ): StackSettings =
    key match
      case "format" => acc.copy(format = acc.format :+ command)
      case "lint"   => acc.copy(lint = acc.lint :+ command)
      case "test"   => acc.copy(test = acc.test :+ command)
