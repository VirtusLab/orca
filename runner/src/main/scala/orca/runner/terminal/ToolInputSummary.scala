package orca.runner.terminal

import scala.annotation.tailrec

/** Produces a short, human-readable summary of a tool call's raw JSON input —
  * the bit the renderer shows in parentheses after the tool name. A
  * deliberately small hand-written JSON string extractor rather than a full
  * parser, since this is purely a display heuristic; input with no field worth
  * heading falls back to naming the fields it does carry.
  *
  * `workDir`, when supplied, relativises paths inside the flow's working
  * directory (`/tmp/orca-AbC/src/Main.scala` → `src/Main.scala`). Paths outside
  * stay absolute, so external file access remains visually obvious.
  */
private[terminal] object ToolInputSummary:

  /** How a matched field's value renders: `Path` is a single path, relativised
    * against `workDir`; `Command` is a shell command line, rendered through
    * [[CommandHeadline]]; `Plain` is free-form text that may interleave paths
    * with other text, so it is only truncated.
    */
  private enum HeadlineKind:
    case Path, Command, Plain

  /** Field names tried against the input's top-level JSON object, in order —
    * the first match wins — each with how its value renders. More specific
    * names come first (`file_path` beats `path`, both beat `pattern`/`query`);
    * the tail holds the generic ones, `name` ahead of `description`.
    */
  private val HeadlineFields: List[(String, HeadlineKind)] =
    List(
      "file_path" -> HeadlineKind.Path,
      "path" -> HeadlineKind.Path,
      "command" -> HeadlineKind.Command,
      "pattern" -> HeadlineKind.Plain,
      "query" -> HeadlineKind.Plain,
      "url" -> HeadlineKind.Plain,
      "rev" -> HeadlineKind.Plain,
      "skill" -> HeadlineKind.Plain,
      "name" -> HeadlineKind.Plain,
      "description" -> HeadlineKind.Plain
    )

  /** Returns an already-truncated headline suitable for rendering after the
    * tool name. Empty string means "no args to show".
    */
  def summarise(
      rawJson: String,
      maxLength: Int,
      workDir: Option[os.Path] = None
  ): String =
    val collapsed = collapseWhitespace(rawJson)
    if collapsed.isEmpty || collapsed == "{}" then ""
    else
      HeadlineFields.iterator
        .flatMap((field, kind) =>
          extractStringField(collapsed, field).map(kind -> _)
        )
        .nextOption() match
        case Some((kind, value)) =>
          s"(${headline(kind, value, maxLength, workDir)})"
        case None => fallback(collapsed, maxLength)

  private def headline(
      kind: HeadlineKind,
      value: String,
      maxLength: Int,
      workDir: Option[os.Path]
  ): String = kind match
    case HeadlineKind.Path =>
      Text.truncate(relativise(value, workDir), maxLength)
    case HeadlineKind.Command => CommandHeadline.render(value, maxLength)
    case HeadlineKind.Plain   => Text.truncate(value, maxLength)

  /** No headline field matched. Show WHICH fields the call carried rather than
    * their values: the values are the reason no field matched (long edit
    * bodies, nested objects), and truncated raw JSON renders them as
    * escaped-newline soup. Input that isn't a JSON object still falls back to
    * the truncated text itself.
    */
  private def fallback(collapsed: String, maxLength: Int): String =
    topLevelFieldNames(collapsed) match
      case Nil   => Text.truncate(collapsed, maxLength)
      case names => Text.truncate(names.mkString("{", ", ", "}"), maxLength)

  /** Convert an absolute path under `workDir` into a relative one; leave
    * anything else (relative paths, paths outside `workDir`, or when `workDir`
    * is None) alone.
    */
  private def relativise(value: String, workDir: Option[os.Path]): String =
    workDir
      .flatMap: wd =>
        val abs = wd.toString
        if value == abs then Some(".")
        else if value.startsWith(s"$abs/") then Some(value.drop(abs.length + 1))
        else None
      .getOrElse(value)

  /** Pre-compiled — `String.replaceAll` recompiles on every call, and this
    * fires once per tool-use event (many per turn on a busy session).
    */
  private val WhitespaceRun: java.util.regex.Pattern =
    java.util.regex.Pattern.compile("\\s+")

  private def collapseWhitespace(raw: String): String =
    WhitespaceRun.matcher(raw).replaceAll(" ").trim

  /** Matches a `"field": "value"` entry — JSON allows whitespace around the
    * colon, and an agent that pretty-prints its tool input would otherwise fall
    * through to [[fallback]] on every call — and walks the value honouring `\"`
    * / `\\` escapes. Returns `None` if the field is absent, its value isn't a
    * string, or the string doesn't terminate. Escapes beyond the common
    * shell/path ones round-trip verbatim.
    */
  private def extractStringField(json: String, field: String): Option[String] =
    val key = s""""$field""""

    // The unescaped string literal starting at `from`, if there is one there.
    def stringAt(from: Int): Option[String] =
      Option
        .when(json.startsWith("\"", from))(from + 1)
        .flatMap(start =>
          findStringEnd(json, start)
            .map(end => unescape(json.substring(start, end)))
        )

    // The name can also occur inside a value — an edit whose `old_string` is
    // itself JSON, say — so keep looking until one occurrence is followed by a
    // colon and a string.
    @tailrec
    def keyFrom(searchAt: Int): Option[String] =
      json.indexOf(key, searchAt) match
        case -1 => None
        case at =>
          val colon = skipSpaces(json, at + key.length)
          if !json.startsWith(":", colon) then keyFrom(at + key.length)
          else stringAt(skipSpaces(json, colon + 1))

    keyFrom(0)

  /** Top-level keys of a JSON object, in order; `Nil` when `json` isn't one.
    * Strings are walked escape-aware, so a brace or quote inside a value can't
    * shift the nesting depth.
    */
  private def topLevelFieldNames(json: String): List[String] =
    @tailrec
    def scan(i: Int, depth: Int, names: List[String]): List[String] =
      if i >= json.length || depth <= 0 then names.reverse
      else
        json.charAt(i) match
          case '"' =>
            findStringEnd(json, i + 1) match
              case None => names.reverse
              case Some(end) =>
                val isKey = depth == 1 &&
                  json.startsWith(":", skipSpaces(json, end + 1))
                val next =
                  if isKey then json.substring(i + 1, end) :: names else names
                scan(end + 1, depth, next)
          case '{' | '[' => scan(i + 1, depth + 1, names)
          case '}' | ']' => scan(i + 1, depth - 1, names)
          case _         => scan(i + 1, depth, names)

    if !json.startsWith("{") then Nil else scan(1, 1, Nil)

  @tailrec
  private def skipSpaces(s: String, i: Int): Int =
    if i < s.length && s.charAt(i).isWhitespace then skipSpaces(s, i + 1) else i

  @tailrec
  private def findStringEnd(s: String, i: Int): Option[Int] =
    if i >= s.length then None
    else
      val ch = s.charAt(i)
      if ch == '\\' then findStringEnd(s, i + 2)
      else if ch == '"' then Some(i)
      else findStringEnd(s, i + 1)

  private def unescape(s: String): String =
    val sb = new StringBuilder(s.length)
    var i = 0
    while i < s.length do
      val ch = s.charAt(i)
      if ch == '\\' && i + 1 < s.length then
        sb.append(replacement(s.charAt(i + 1)))
        i += 2
      else
        sb.append(ch)
        i += 1
    sb.toString

  private def replacement(escaped: Char): Char = escaped match
    case '"'   => '"'
    case '\\'  => '\\'
    case '/'   => '/'
    case 'n'   => '\n'
    case 't'   => '\t'
    case 'r'   => '\r'
    case other => other
