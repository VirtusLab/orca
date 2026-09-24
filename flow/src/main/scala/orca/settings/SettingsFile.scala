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
        "that runs nothing and exits 0, silently disabling the gate; " +
        "comment out the whole line instead"
    case DuplicateKey(line, key) =>
      s"line $line: `$key` appears twice — agent keys are single-valued"
    case InvalidAgentSpec(line, key, problem) =>
      s"line $line: $key: $problem"
    case NotAllowedInGlobal(line, key) =>
      s"line $line: `$key` is not valid in the user-global settings file — " +
        s"stack commands (${StackKey.values.map(_.raw).mkString(", ")}) " +
        "are per-project; valid keys here: " +
        AgentKey.values.map(_.raw).mkString(", ")

/** Which settings file is being parsed: the per-project file accepts every key;
  * the user-global file accepts only agent keys (stack commands are per-project
  * by nature).
  */
private[orca] enum SettingsScope:
  case Project, UserGlobal

/** The result of parsing one settings file: the stack commands and the agent
  * role assignments it names. `stack` is `None` when no stack key is configured
  * — the auto-discovery trigger (ADR 0019 amendment 2026-09-23); an explicit
  * `key = off` configures that key with no commands.
  */
private[orca] case class ParsedSettings(
    stack: Option[StackSettings],
    agents: AgentSettings
)

/** Strict line format for `.orca/settings.properties`: `#` comments (first
  * non-space char is `#`), `key = value` with the value taken verbatim
  * (trimmed) after the first `=` — no comment stripping, so a `#` inside the
  * value is command text. Repeated stack keys append in file order, repeated
  * agent keys are rejected, an empty value is equivalent to omitting the key. A
  * stack key's value may also be the literal `off`, which explicitly disables
  * that gate (same runtime effect as omitting the key, but it counts as
  * configured, see [[ParsedSettings]]). Hand-rolled rather than
  * `java.util.Properties`, whose backslash/unicode escape handling would mangle
  * shell commands (ADR 0019).
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
      Right(ParsedSettings(None, AgentSettings.empty)): Either[
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
    Line.of(line) match
      case Line.Inert => Right(acc)
      case Line.Malformed(text) =>
        Left(SettingsError.NoAssignment(number, text))
      case Line.Assignment(rawKey, value) =>
        SettingKey.fromRaw(rawKey) match
          case None => Left(SettingsError.UnknownKey(number, rawKey))
          case Some(key: StackKey) =>
            if scope == SettingsScope.UserGlobal then
              Left(SettingsError.NotAllowedInGlobal(number, rawKey))
            else parseStackKey(acc, key, value, number)
          case Some(key: AgentKey) =>
            parseAgentKey(acc, key, value, number)

  private def parseStackKey(
      acc: ParsedSettings,
      key: StackKey,
      value: String,
      number: Int
  ): Either[SettingsError, ParsedSettings] =
    val stackSoFar = acc.stack.getOrElse(StackSettings.empty)
    StackValue.parse(value) match
      case StackValue.Run(command) =>
        Right(acc.copy(stack = Some(key.appendTo(stackSoFar, command))))
      case StackValue.Off   => Right(acc.copy(stack = Some(stackSoFar)))
      case StackValue.Empty => Right(acc)
      case StackValue.CommentedOut =>
        Left(SettingsError.CommentedValue(number, key.raw))

  private def parseAgentKey(
      acc: ParsedSettings,
      key: AgentKey,
      value: String,
      number: Int
  ): Either[SettingsError, ParsedSettings] =
    // A value starting with `#` is rejected for agent keys too, so the error
    // names the commented-out line rather than an unknown harness `#`.
    if value.startsWith("#") then
      Left(SettingsError.CommentedValue(number, key.raw))
    else if value.isEmpty then Right(acc)
    else if acc.agents.get(key).isDefined then
      Left(SettingsError.DuplicateKey(number, key.raw))
    else
      AgentSpec.parse(value) match
        case Left(problem) =>
          Left(SettingsError.InvalidAgentSpec(number, key.raw, problem))
        case Right(spec) =>
          Right(acc.copy(agents = acc.agents.updated(key, Some(spec))))

  /** The header comment lines [[render]] places at the top of every project
    * settings file. Only a live `format`/`lint`/`test` line with a value
    * (including an explicit `= off`) counts as "configured" and keeps
    * auto-discovery from running.
    */
  val Header: String =
    "# orca settings — edit freely, commit with the project.\n" +
      "# format/lint/test: one shell command per key; `off` disables the " +
      "gate. Delete the stack lines (or the whole file) to re-run " +
      "auto-discovery.\n" +
      "# planningAgent/codingAgent/reviewAgent (harness[:model]): override " +
      "the global settings file; a flow's own code overrides both."

  /** The full settings-file text for `entries` under [[Header]],
    * newline-terminated. A [[SettingsEntry.Command]]'s comment renders as one
    * `# ` line per line of comment text, directly above the `key = command`
    * line, so a multi-line comment stays parseable.
    */
  def render(entries: List[SettingsEntry]): String =
    (Header :: entries.map(renderEntry)).mkString("", "\n", "\n")

  /** The rendered entry block for `entries` WITHOUT [[Header]],
    * newline-terminated. The append shape for a file that already exists but
    * configures no stack key (an agents-only hand-written file): discovery
    * appends its stack entries below the user's untouched agent lines instead
    * of overwriting the whole file.
    */
  def renderAppend(entries: List[SettingsEntry]): String =
    entries.map(renderEntry).mkString("", "\n", "\n")

  /** The header comment [[renderGlobal]] places at the top of a fresh
    * user-global settings file — distinct wording from [[Header]], which is
    * stack-discovery-specific and doesn't apply to the agent-only global file.
    */
  private[orca] val GlobalHeader: String =
    "# orca global settings — role agents; edit freely. " +
      "Values: harness[:model]"

  /** A fresh global settings file: [[GlobalHeader]] plus one `key = value` line
    * per role `agents` sets, in planning/coding/review order. Round-trips
    * through `SettingsFile.parse(_, SettingsScope.UserGlobal)`.
    */
  private[orca] def renderGlobal(agents: AgentSettings): String =
    (GlobalHeader :: agents.entries.map(renderAgentLine))
      .mkString("", "\n", "\n")

  /** Surgical update of an existing global file's text: each role `agents` sets
    * that already has a live line in `content` is replaced in place (same
    * first-`=` key extraction as the parser); a set role with no existing line
    * is appended at the end; everything else — comments, blank lines, and a
    * role `agents` leaves unset — passes through untouched. Precondition:
    * `content` parses cleanly under [[SettingsScope.UserGlobal]] (the caller's
    * job — this rewrites known-good text, not recovers bad text).
    */
  private[orca] def updateGlobal(
      content: String,
      agents: AgentSettings
  ): String =
    val toSet = agents.entries
    val toSetByKey = toSet.toMap
    val (revLines, remaining) =
      content.linesIterator.foldLeft((List.empty[String], toSetByKey.keySet)):
        case ((acc, pending), line) =>
          liveKey(line) match
            case Some(key: AgentKey) if pending(key) =>
              (renderAgentLine(key, toSetByKey(key)) :: acc, pending - key)
            case _ => (line :: acc, pending)
    val appended = toSet.collect:
      case (key, spec) if remaining(key) => renderAgentLine(key, spec)
    (revLines.reverse ::: appended).mkString("", "\n", "\n")

  private def renderAgentLine(key: AgentKey, spec: AgentSpec): String =
    val model = spec.model.fold("")(":" + _)
    s"${key.raw} = ${AgentSpec.harnessNameFor(spec.backend)}$model"

  private def renderEntry(entry: SettingsEntry): String =
    entry match
      case SettingsEntry.Command(key, command, comment) =>
        val commandLine = s"${key.raw} = ${command.value}"
        comment.filter(!_.isBlank) match
          case Some(text) =>
            text.linesIterator
              .map("# " + _)
              .mkString("", "\n", "\n") + commandLine
          case None => commandLine
      case SettingsEntry.Unset(key, reason) =>
        // A live `off` line, not a comment: an unset gate must still count as
        // "configured" so discovery doesn't re-run over the same absence
        // every time. The reason is purely informative, one `#` line above.
        s"# ${collapseWhitespace(reason)}\n${key.raw} = ${StackValue.OffLiteral}"
      case SettingsEntry.Demoted(key, command, reason) =>
        // Collapsed to stay one physical `#` line.
        s"$SkippedPrefix${key.raw} = ${collapseWhitespace(command)} " +
          s"(${collapseWhitespace(reason)})"
      case SettingsEntry.Off(key) => s"${key.raw} = ${StackValue.OffLiteral}"

  private def collapseWhitespace(s: String): String =
    TextUtil.collapseWhitespace(s)

  /** The known key a live line assigns, whatever its value. Looser than
    * [[parse]] on purpose: the text edits ([[updateGlobal]],
    * [[stripStackLines]]) treat `format =` as a stack line although it
    * configures nothing.
    */
  private def liveKey(line: String): Option[SettingKey] =
    Line.of(line) match
      case Line.Assignment(rawKey, _)     => SettingKey.fromRaw(rawKey)
      case Line.Inert | Line.Malformed(_) => None

  /** Index set of the contiguous `#`-comment lines directly above `index` in
    * `lines` — [[renderEntry]]'s evidence/reason citation for the live line at
    * `index`. Stops at a blank line, one of [[Header]]'s lines, or a previous
    * live line (that comment run belongs to a different entry), so a
    * hand-written comment merely mentioning a stack key is never swept in.
    */
  private def evidenceAbove(
      lines: IndexedSeq[String],
      headerLines: Set[String],
      index: Int
  ): Set[Int] =
    def bare(line: String): String = line.stripLineEnd
    val evidence = scala.collection.mutable.Set.empty[Int]
    var j = index - 1
    while j >= 0 && bare(lines(j)).trim.startsWith("#") &&
      !headerLines(bare(lines(j)))
    do
      evidence += j
      j -= 1
    evidence.toSet

  /** `content` with every LIVE `format`/`lint`/`test` line removed, plus each
    * one's evidence comment block (see [[evidenceAbove]]) and every
    * [[SettingsEntry.Demoted]] line, which need not sit above a live line — the
    * surgical edit behind the shell's "re-discover project stack settings"
    * action (ADR 0021 §4/§8). If `content` parses, the result parses with
    * `stack = None`. Everything else — agent keys, blank lines,
    * unrelated/hand-written comments, [[Header]], ordering — passes through
    * with its original line terminator untouched.
    */
  private[orca] def stripStackLines(content: String): String =
    val lines = content.linesWithSeparators.toIndexedSeq
    def bare(line: String): String = line.stripLineEnd
    val headerLines = Header.linesIterator.toSet
    val liveIdx =
      lines.indices.filter(i => isStackKeyLine(bare(lines(i)))).toSet
    val evidenceIdx = liveIdx.flatMap(evidenceAbove(lines, headerLines, _))
    val skippedIdx =
      lines.indices.filter(i => isSkippedStackLine(bare(lines(i)))).toSet
    val toRemove = liveIdx ++ evidenceIdx ++ skippedIdx
    lines.zipWithIndex.collect {
      case (line, i) if !toRemove(i) => line
    }.mkString

  /** Starts a rendered [[SettingsEntry.Demoted]] line. */
  private val SkippedPrefix = "# skipped: "

  private def isSkippedStackLine(line: String): Boolean =
    val trimmed = line.trim
    trimmed.startsWith(SkippedPrefix) &&
    isStackKeyLine(trimmed.drop(SkippedPrefix.length))

  private def isStackKeyLine(line: String): Boolean =
    liveKey(line) match
      case Some(_: StackKey) => true
      case _                 => false

/** The shape of one settings-file line — the single definition shared by the
  * parser and the text edits.
  */
private enum Line:
  /** Blank, or a `#` comment. */
  case Inert

  /** Neither inert nor containing `=`; `text` is the trimmed line. */
  case Malformed(text: String)

  /** Split at the FIRST `=`, key and value trimmed, so commands containing `=`
    * (e.g. `FOO=bar cargo check`) survive intact.
    */
  case Assignment(rawKey: String, value: String)

private object Line:
  def of(line: String): Line =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then Inert
    else
      trimmed.indexOf('=') match
        case -1 => Malformed(trimmed)
        case eq => Assignment(trimmed.take(eq).trim, trimmed.drop(eq + 1).trim)
