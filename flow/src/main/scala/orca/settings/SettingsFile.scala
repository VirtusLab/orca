package orca.settings

import orca.StackSettings
import orca.util.TextUtil

/** A problem found in `.orca/settings.properties` — the `Left` of
  * [[SettingsFile.parse]]. Line numbers are 1-based.
  */
private[settings] enum SettingsError:
  case NoAssignment(line: Int, text: String)
  case UnknownKey(line: Int, key: String)
  case CommentedValue(line: Int, key: String)
  case DuplicateKey(line: Int, key: String)
  case InvalidAgentSpec(line: Int, key: String, problem: String)
  case NotAllowedInGlobal(line: Int, key: String)

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
    case DuplicateKey(line, key) =>
      s"line $line: `$key` appears twice — agent keys are single-valued"
    case InvalidAgentSpec(line, key, problem) =>
      s"line $line: $key: $problem"
    case NotAllowedInGlobal(line, key) =>
      s"line $line: `$key` is not valid in the user-global settings file — " +
        "stack commands (format, lint, test) are per-project; valid keys " +
        "here: planningAgent, codingAgent, reviewAgent"

/** The closed set of settings-file keys; `raw` is the exact on-disk spelling
  * (keys are case-sensitive). Split into [[StackKey]] and [[AgentKey]] so
  * `append` and the agent-key handling each match only their own cases —
  * exhaustively, with no wildcard arm over the full six-case set — a key added
  * to either enum without matching code added everywhere fails to compile
  * instead of silently falling through.
  */
private[settings] sealed trait SettingKey:
  def raw: String

/** The stack-command keys: project-only, values append in file order. */
private[orca] enum StackKey(val raw: String) extends SettingKey:
  case Format extends StackKey("format")
  case Lint extends StackKey("lint")
  case Test extends StackKey("test")

/** The agent role keys: valid in both scopes, single-valued. */
private[settings] enum AgentKey(val raw: String) extends SettingKey:
  case PlanningAgent extends AgentKey("planningAgent")
  case CodingAgent extends AgentKey("codingAgent")
  case ReviewAgent extends AgentKey("reviewAgent")

private[settings] object SettingKey:
  val values: Array[SettingKey] = StackKey.values ++ AgentKey.values
  def fromRaw(s: String): Option[SettingKey] = values.find(_.raw == s)

/** Which settings file is being parsed: the per-project file accepts every key;
  * the user-global file accepts only agent keys (stack commands are per-project
  * by nature).
  */
private[orca] enum SettingsScope:
  case Project, UserGlobal

/** The result of parsing one settings file: the stack commands and the agent
  * role assignments it names.
  */
private[orca] case class ParsedSettings(
    stack: StackSettings,
    agents: AgentSettings
)

/** Strict line format for `.orca/settings.properties`: `#` comments (a line
  * whose first non-space char is `#`), `key = value` with the value taken
  * verbatim (trimmed) after the first `=` — always, with no comment stripping,
  * so a `#` inside the value is command text — repeated stack keys append in
  * file order, repeated agent keys are rejected, an empty value is equivalent
  * to omitting the key. Hand-rolled rather than `java.util.Properties`, whose
  * backslash/unicode escape handling would mangle shell commands (ADR 0019).
  */
private[orca] object SettingsFile:

  /** Left = a [[SettingsError]] naming the offending line (and, for an unknown
    * key, the valid keys). `scope` gates the stack keys (`format`/`lint`/
    * `test`), which are project-only.
    */
  def parse(
      content: String,
      scope: SettingsScope
  ): Either[SettingsError, ParsedSettings] =
    content.linesIterator.zipWithIndex.foldLeft(
      Right(ParsedSettings(StackSettings.empty, AgentSettings.empty)): Either[
        SettingsError,
        ParsedSettings
      ]
    ):
      case (problem @ Left(_), _)      => problem
      case (Right(acc), (line, index)) => parseLine(acc, line, index + 1, scope)

  private def parseLine(
      acc: ParsedSettings,
      line: String,
      number: Int,
      scope: SettingsScope
  ): Either[SettingsError, ParsedSettings] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then Right(acc)
    else
      splitAssignment(trimmed) match
        case None => Left(SettingsError.NoAssignment(number, trimmed))
        case Some((rawKey, value)) =>
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
              else
                key match
                  case stackKey: StackKey =>
                    if scope == SettingsScope.UserGlobal then
                      Left(SettingsError.NotAllowedInGlobal(number, rawKey))
                    else
                      Right(
                        acc.copy(stack = append(acc.stack, stackKey, value))
                      )
                  case agentKey: AgentKey =>
                    parseAgentKey(acc, agentKey, rawKey, value, number)

  private def parseAgentKey(
      acc: ParsedSettings,
      key: AgentKey,
      rawKey: String,
      value: String,
      number: Int
  ): Either[SettingsError, ParsedSettings] =
    val alreadySet = key match
      case AgentKey.PlanningAgent => acc.agents.planning.isDefined
      case AgentKey.CodingAgent   => acc.agents.coding.isDefined
      case AgentKey.ReviewAgent   => acc.agents.review.isDefined
    if alreadySet then Left(SettingsError.DuplicateKey(number, rawKey))
    else
      AgentSpec.parse(value) match
        case Left(problem) =>
          Left(SettingsError.InvalidAgentSpec(number, rawKey, problem))
        case Right(spec) =>
          val agents = key match
            case AgentKey.PlanningAgent =>
              acc.agents.copy(planning = Some(spec))
            case AgentKey.CodingAgent => acc.agents.copy(coding = Some(spec))
            case AgentKey.ReviewAgent => acc.agents.copy(review = Some(spec))
          Right(acc.copy(agents = agents))

  /** The header comment lines [[render]] places at the top of every settings
    * file.
    */
  val Header: String =
    "# orca settings — edit freely, commit with the project.\n" +
      "# Delete the stack lines (format/lint/test, commented ones too) to " +
      "re-run auto-discovery."

  /** The full settings-file text for `entries` under [[Header]],
    * newline-terminated. A [[SettingsEntry.Command]]'s comment renders as its
    * own `# ` line(s) directly above the `key = command` line — one `# ` line
    * per line of comment text, so a multi-line comment stays parseable.
    */
  def render(entries: List[SettingsEntry]): String =
    (Header :: entries.map(renderEntry)).mkString("", "\n", "\n")

  /** The rendered entry block for `entries` WITHOUT [[Header]],
    * newline-terminated. The append shape for a file that already exists but
    * carries no stack lines (an agents-only hand-written file): discovery
    * appends its stack entries below the user's untouched agent lines instead
    * of overwriting the whole file. Shares [[renderEntry]] with [[render]] so
    * the entry formatting lives in one place.
    */
  def renderAppend(entries: List[SettingsEntry]): String =
    entries.map(renderEntry).mkString("", "\n", "\n")

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
    TextUtil.collapseNewlines(s)

  private def collapseWhitespace(s: String): String =
    TextUtil.collapseWhitespace(s)

  private def append(
      acc: StackSettings,
      key: StackKey,
      command: String
  ): StackSettings =
    key match
      case StackKey.Format => acc.copy(format = acc.format :+ command)
      case StackKey.Lint   => acc.copy(lint = acc.lint :+ command)
      case StackKey.Test   => acc.copy(test = acc.test :+ command)

  /** Split a `key = value` line at the FIRST `=`: the trimmed key and the
    * verbatim-but-trimmed value (everything after the first `=` belongs to the
    * value, so commands containing `=` — e.g. `FOO=bar cargo check` — survive
    * intact). `None` when the line has no `=`. The single definition of "which
    * key does this line name", shared by [[parseLine]] and [[hasStackLines]] so
    * a control byte `String.trim` strips (but a `\s` regex would not match) can
    * never make the discovery gate and the parser disagree about whether a line
    * is a live stack key.
    */
  private def splitAssignment(line: String): Option[(String, String)] =
    line.indexOf('=') match
      case -1 => None
      case eq => Some((line.take(eq).trim, line.drop(eq + 1).trim))

  /** True when any line of `content` names a stack key — live or commented. The
    * discovery trigger (ADR 0020): a discovery-written file always carries at
    * least commented stack lines, so only a hand-written file with no stack
    * content at all re-triggers discovery.
    *
    * Uses the parser's own [[splitAssignment]] key extraction (after stripping
    * a leading `#` comment marker so a commented stack line still counts),
    * rather than a second regex: the two must agree on what a stack key is, and
    * `String.trim`'s control-byte stripping is a strict superset of a regex
    * `\s`, so a divergent second definition could report "no stack line" for a
    * line the parser reads as a live stack key.
    */
  def hasStackLines(content: String): Boolean =
    content.linesIterator.exists(namesStackKey)

  private def namesStackKey(line: String): Boolean =
    val uncommented = line.trim.stripPrefix("#")
    splitAssignment(uncommented) match
      case Some((rawKey, _)) =>
        SettingKey.fromRaw(rawKey) match
          case Some(_: StackKey) => true
          case Some(_: AgentKey) => false
          case None              => false
      case None => false
