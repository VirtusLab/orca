package orca.settings

import orca.StackSettings
import orca.util.TextUtil

/** A problem found in `.orca/settings.properties` — the `Left` of
  * [[SettingsFile.parse]]. Line numbers are 1-based.
  */
private[orca] enum SettingsError:
  case NoAssignment(line: Int, text: String)
  case UnknownKey(line: Int, key: String)
  case CommentedValue(line: Int, key: String)

  /** Human-readable problem naming the offending line (the lifecycle aborts
    * with it before any tree mutation).
    */
  def message: String = this match
    case NoAssignment(line, text) =>
      s"line $line: `$text` is not a `#` comment and has no `=` " +
        "— expected `key = value`"
    case UnknownKey(line, key) =>
      s"line $line: unknown key `$key` — valid keys: " +
        SettingKey.values.map(_.raw).sorted.mkString(", ")
    case CommentedValue(line, key) =>
      s"line $line: the value of `$key` starts with `#` — under `bash -c` " +
        "that runs nothing and exits 0, silently disabling the task; " +
        "comment out the whole line instead"

/** The closed set of settings-file keys; `raw` is the exact on-disk spelling
  * (keys are case-sensitive).
  */
private[orca] enum SettingKey(val raw: String):
  case Format extends SettingKey("format")
  case Lint extends SettingKey("lint")
  case Test extends SettingKey("test")

private[orca] object SettingKey:
  def fromRaw(s: String): Option[SettingKey] = values.find(_.raw == s)

/** Strict line format for `.orca/settings.properties`: `#` comments (a line
  * whose first non-space char is `#`), `key = value` with the value taken
  * verbatim (trimmed) after the first `=` — always, with no comment stripping,
  * so a `#` inside the value is command text — repeated keys append in file
  * order, an empty value is equivalent to omitting the key. Hand-rolled rather
  * than `java.util.Properties`, whose backslash/unicode escape handling would
  * mangle shell commands (ADR 0019).
  */
private[orca] object SettingsFile:

  /** Left = a [[SettingsError]] naming the offending line (and, for an unknown
    * key, the valid keys).
    */
  def parse(content: String): Either[SettingsError, StackSettings] =
    content.linesIterator.zipWithIndex.foldLeft(
      Right(StackSettings.empty): Either[SettingsError, StackSettings]
    ):
      case (problem @ Left(_), _)      => problem
      case (Right(acc), (line, index)) => parseLine(acc, line, index + 1)

  private def parseLine(
      acc: StackSettings,
      line: String,
      number: Int
  ): Either[SettingsError, StackSettings] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then Right(acc)
    else
      trimmed.indexOf('=') match
        case -1 => Left(SettingsError.NoAssignment(number, trimmed))
        case eq =>
          val rawKey = trimmed.take(eq).trim
          // Everything after the FIRST `=` belongs to the value, so commands
          // containing `=` (e.g. `FOO=bar cargo check`) survive intact.
          val value = trimmed.drop(eq + 1).trim
          SettingKey.fromRaw(rawKey) match
            case None      => Left(SettingsError.UnknownKey(number, rawKey))
            case Some(key) =>
              // A value starting with `#` (e.g. `lint = # disabled`) runs
              // nothing under `bash -c` and exits 0, silently turning the
              // gate off — rejected so the whole line is commented out
              // instead.
              if value.startsWith("#") then
                Left(SettingsError.CommentedValue(number, rawKey))
              else if value.isEmpty then Right(acc)
              else Right(append(acc, key, value))

  /** The header comment lines [[render]] places at the top of every settings
    * file.
    */
  val Header: String =
    "# orca stack settings — edit freely, commit with the project.\n" +
      "# Delete this file to re-run auto-discovery."

  /** The full settings-file text for `entries` under [[Header]],
    * newline-terminated. A [[SettingsEntry.Command]]'s comment renders as its
    * own `# ` line(s) directly above the `key = command` line — one `# ` line
    * per line of comment text, so a multi-line comment stays parseable.
    */
  def render(entries: List[SettingsEntry]): String =
    (Header :: entries.map(renderEntry)).mkString("", "\n", "\n")

  private def renderEntry(entry: SettingsEntry): String =
    entry match
      case SettingsEntry.Command(key, command, comment) =>
        // Newlines in the command collapse to single spaces so the entry stays
        // one physical line (an LLM-sourced multi-line string would otherwise
        // wedge the next parse).
        val commandLine = s"$key = ${collapseNewlines(command)}"
        // A blank comment renders as absent — a lone `# ` line above the
        // command would carry no information.
        comment.filter(!_.isBlank) match
          case Some(text) =>
            text.linesIterator
              .map("# " + _)
              .mkString("", "\n", "\n") + commandLine
          case None => commandLine
      case SettingsEntry.Unset(key, reason) =>
        // Whitespace runs in the reason collapse to single spaces — the same
        // one-physical-line guarantee as the command above.
        s"# $key =   (${collapseWhitespace(reason)})"
      case SettingsEntry.Demoted(key, command, reason) =>
        // Both parts collapse like Unset's reason: the whole entry must stay
        // one physical `#` line or the tail would parse as live commands.
        s"# $key = ${collapseWhitespace(command)}   " +
          s"(${collapseWhitespace(reason)})"

  private def collapseNewlines(s: String): String =
    s.replaceAll("""\s*\R\s*""", " ")

  private def collapseWhitespace(s: String): String =
    TextUtil.collapseWhitespace(s)

  private def append(
      acc: StackSettings,
      key: SettingKey,
      command: String
  ): StackSettings =
    key match
      case SettingKey.Format => acc.copy(format = acc.format :+ command)
      case SettingKey.Lint   => acc.copy(lint = acc.lint :+ command)
      case SettingKey.Test   => acc.copy(test = acc.test :+ command)
