package orca.util

/** Parsed result of [[PromptResource.parseWithMetadata]]: frontmatter key/value
  * pairs plus the body text below the closing `---`.
  */
private[orca] case class ParsedPrompt(
    metadata: Map[String, String],
    body: String,
    /** Whether the text opened a `---` frontmatter block at all. A file without
      * one carries no metadata by design; one that opened a block and still has
      * empty `metadata` has a block the parser could not read — unterminated,
      * mis-delimited, or holding no `key: value` line.
      */
    hasFrontmatter: Boolean,
    /** Frontmatter lines that are not a `key: value` entry — typically a value
      * wrapped onto the next line. Kept so the caller can refuse the file
      * rather than load a truncated value.
      */
    unreadLines: List[String]
)

/** Loads prompt templates from classpath resources, one `.md` file per template
  * under `src/main/resources/<pkg>/prompts/<name>.md`.
  *
  * Templates use `{{name}}` placeholders. Dynamic values go through [[render]];
  * static fragments shared between templates should be substituted once at
  * object initialization. Templates needing richer metadata carry a YAML-ish
  * frontmatter block between two `---` markers; use [[loadWithMetadata]].
  *
  * Missing resources fail fast as `RuntimeException` at object-init time.
  */
private[orca] object PromptResource:

  /** Read a classpath resource as UTF-8 text. The path is absolute relative to
    * the classpath root (use a leading `/`).
    */
  def load(path: String): String =
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse(
      throw new RuntimeException(
        s"prompt resource not found on classpath: $path"
      )
    )
    try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  /** Substitute `{{name}}` placeholders in `template` with the supplied `(name
    * -> value)` pairs. Unreferenced substitutions are ignored; a placeholder
    * with no substitution is a defect — a template and its call site that have
    * drifted apart — and throws rather than sending the literal `{{name}}` to
    * an agent.
    *
    * Only `template` is scanned, never the result: a substituted value can
    * itself hold `{{…}}` (a diff of a prompt file under review does), and that
    * is content, not a placeholder.
    */
  def render(template: String, substitutions: (String, String)*): String =
    val supplied = substitutions.map((key, _) => key).toSet
    val unfilled = Placeholder
      .findAllMatchIn(template)
      .map(_.group(1))
      .filterNot(supplied.contains)
      .distinct
      .toList
    if unfilled.nonEmpty then
      throw new RuntimeException(
        s"prompt template placeholders with no substitution: " +
          unfilled.mkString(", ")
      )
    substitutions.foldLeft(template):
      case (acc, (key, value)) => acc.replace(s"{{$key}}", value)

  private val Placeholder = """\{\{(\w+)\}\}""".r

  /** Load a resource and split YAML-ish frontmatter from the body.
    *
    * The expected shape:
    *
    * {{{
    * ---
    * key: value
    * other: "quoted value with escapes \n and \"quotes\""
    * ---
    *
    * body text follows the closing delimiter...
    * }}}
    *
    * Only single-line `key: value` pairs are recognized; any other non-blank,
    * non-comment line lands in [[ParsedPrompt.unreadLines]]. Double-quoted
    * values are unescaped using YAML double-quoted rules (`\n`, `\t`, `\r`,
    * `\"`, `\\`); unrecognized backslash sequences are preserved verbatim. A
    * file without a leading `---` is treated as all body, empty metadata.
    */
  def loadWithMetadata(path: String): ParsedPrompt =
    parseWithMetadata(load(path))

  /** [[loadWithMetadata]] over text already in hand — a file read from disk
    * rather than a classpath resource.
    */
  def parseWithMetadata(raw: String): ParsedPrompt =
    // A hand-written or `core.autocrlf`-checked-out file arrives with CRLF
    // endings, or a BOM an editor added; the delimiter scan below matches
    // neither, and would report the frontmatter as missing rather than absent.
    val text = raw.stripPrefix("\uFEFF").replace("\r\n", "\n")
    // Looser than the `---\n` the parse below needs: `--- ` with a trailing
    // space, or `---` at EOF, is a frontmatter attempt that failed, not a file
    // that never tried.
    val opened = text.startsWith("---")
    if !text.startsWith("---\n") then ParsedPrompt(Map.empty, text, opened, Nil)
    else
      val afterOpen = text.substring(4) // skip "---\n"
      val closeIdx = afterOpen.indexOf("\n---")
      if closeIdx < 0 then
        ParsedPrompt(Map.empty, text, hasFrontmatter = true, Nil)
      else
        val frontmatter = afterOpen.substring(0, closeIdx)
        // skip past "\n---" plus the trailing newline (if present)
        val bodyStart = closeIdx + "\n---".length
        val body = afterOpen
          .substring(bodyStart)
          .stripPrefix("\n") // remove blank line after closing ---
          .stripPrefix("\n") // and one more if the file separated them
        val (unreadLines, entries) = frontmatter.linesIterator
          .flatMap(parseFrontmatterLine)
          .toList
          .partitionMap(identity)
        ParsedPrompt(entries.toMap, body, hasFrontmatter = true, unreadLines)

  /** `None` for a blank or comment line, `Left(line)` for a line that is not a
    * `key: value` entry: indented, or without a word-like key before its first
    * colon.
    */
  private def parseFrontmatterLine(
      line: String
  ): Option[Either[String, (String, String)]] =
    val trimmed = line.stripTrailing
    if trimmed.isBlank || trimmed.stripLeading.startsWith("#") then None
    else
      trimmed match
        case Entry(key, raw) =>
          val value =
            if raw.length >= 2 && raw.head == '"' && raw.last == '"' then
              unescapeYamlDoubleQuoted(raw.substring(1, raw.length - 1))
            else raw
          Some(Right(key -> value))
        case _ => Some(Left(trimmed))

  private val Entry = """([A-Za-z][\w-]*)\s*:\s*(.*)""".r

  /** Process backslash escapes per YAML double-quoted scalar rules. */
  private def unescapeYamlDoubleQuoted(s: String): String =
    val sb = new StringBuilder(s.length)
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == '\\' && i + 1 < s.length then
        val next = s.charAt(i + 1)
        val toAppend = next match
          case 'n'  => "\n"
          case 't'  => "\t"
          case 'r'  => "\r"
          case '"'  => "\""
          case '\\' => "\\"
          case other =>
            "\\" + other // preserve unknown sequences verbatim
        val _ = sb.append(toAppend)
        i += 2
      else
        val _ = sb.append(c)
        i += 1
    sb.toString
