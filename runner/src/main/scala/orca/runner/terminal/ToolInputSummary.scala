package orca.runner.terminal

import scala.annotation.tailrec

/** Produces a short, human-readable summary of a tool call's raw JSON input —
  * the bit the renderer shows in parentheses after the tool name. A
  * deliberately small hand-written JSON string extractor rather than a full
  * parser, since this is purely a display heuristic; unmatched input falls back
  * to truncated JSON.
  *
  * `workDir`, when supplied, relativises paths inside the flow's working
  * directory (`/tmp/orca-AbC/src/Main.scala` → `src/Main.scala`). Paths outside
  * stay absolute, so external file access remains visually obvious.
  */
private[terminal] object ToolInputSummary:

  /** Ordered field names tried against the input's top-level JSON object; the
    * first match wins. Order matters — `file_path` beats `path`, which beats
    * the more generic `pattern`/`query`.
    */
  private val HeadlineFields: List[String] =
    List(
      "file_path",
      "path",
      "command",
      "pattern",
      "query",
      "url",
      "description"
    )

  /** Headline fields whose values are single paths to relativise against
    * `workDir`. The others are free-form strings that may interleave paths with
    * other text, so they're left alone.
    */
  private val PathFields: Set[String] = Set("file_path", "path")

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
        .flatMap(field => extractStringField(collapsed, field).map(field -> _))
        .nextOption() match
        case Some((field, value)) =>
          val displayed =
            if PathFields.contains(field) then relativise(value, workDir)
            else value
          s"(${Text.truncate(displayed, maxLength)})"
        case None => Text.truncate(collapsed, maxLength)

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

  /** Matches a `"field":"value"` entry and walks the value honouring `\"` /
    * `\\` escapes. Returns `None` if the field is absent or the string doesn't
    * terminate. Escapes beyond the common shell/path ones round-trip verbatim.
    */
  private def extractStringField(json: String, field: String): Option[String] =
    val needle = s""""$field":""""
    val start = json.indexOf(needle)
    if start < 0 then None
    else
      val valueStart = start + needle.length
      findStringEnd(json, valueStart).map: end =>
        unescape(json.substring(valueStart, end))

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
