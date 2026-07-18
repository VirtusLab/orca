package orca.util

/** Parsed result of [[PromptResource.loadWithMetadata]]: frontmatter key/value
  * pairs plus the body text below the closing `---`.
  */
private[util] case class ParsedPrompt(
    metadata: Map[String, String],
    body: String
)

/** Loads prompt templates from classpath resources. The convention is one `.md`
  * file per template, placed under `src/main/resources/<pkg>/prompts/<name>.md`
  * — keeping the prose out of `.scala` files makes the text easier to edit (no
  * `.stripMargin` margins, no double-escaped quotes) and treats prompts as the
  * user-facing artifacts they are.
  *
  * Templates use `{{name}}` placeholders. Dynamic values supplied at call time
  * go through [[render]]; static fragments shared between templates (e.g. a
  * common rules block) should be substituted once at object initialization to
  * keep per-call cost minimal.
  *
  * Templates that need richer metadata (e.g. a reviewer agent with a
  * machine-readable description for an LLM-driven selector) carry a YAML-ish
  * frontmatter block between two `---` markers; use [[loadWithMetadata]] to
  * parse it.
  *
  * Missing-resource failures surface as `RuntimeException` at object-init time
  * — desirable fail-fast behaviour for what is effectively a packaging mistake.
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
    * -> value)` pairs. Unknown placeholders are left intact; unreferenced
    * substitutions are ignored.
    */
  def render(template: String, substitutions: (String, String)*): String =
    substitutions.foldLeft(template):
      case (acc, (key, value)) => acc.replace(s"{{$key}}", value)

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
    * Only single-line `key: value` pairs are recognized. Double-quoted values
    * are unescaped using YAML double-quoted rules (`\n`, `\t`, `\r`, `\"`,
    * `\\`); unrecognized backslash sequences are preserved verbatim. A file
    * without a leading `---` is treated as all body, empty metadata.
    */
  def loadWithMetadata(path: String): ParsedPrompt =
    val raw = load(path)
    if !raw.startsWith("---\n") then ParsedPrompt(Map.empty, raw)
    else
      val afterOpen = raw.substring(4) // skip "---\n"
      val closeIdx = afterOpen.indexOf("\n---")
      if closeIdx < 0 then ParsedPrompt(Map.empty, raw)
      else
        val frontmatter = afterOpen.substring(0, closeIdx)
        // skip past "\n---" plus the trailing newline (if present)
        val bodyStart = closeIdx + "\n---".length
        val body = afterOpen
          .substring(bodyStart)
          .stripPrefix("\n") // remove blank line after closing ---
          .stripPrefix("\n") // and one more if the file separated them
        val metadata = frontmatter.linesIterator
          .flatMap(parseFrontmatterLine)
          .toMap
        ParsedPrompt(metadata, body)

  private def parseFrontmatterLine(line: String): Option[(String, String)] =
    val trimmed = line.stripTrailing
    if trimmed.isEmpty || trimmed.startsWith("#") then None
    else
      val colonIdx = trimmed.indexOf(':')
      if colonIdx <= 0 then None
      else
        val key = trimmed.substring(0, colonIdx).trim
        val raw = trimmed.substring(colonIdx + 1).trim
        val value =
          if raw.length >= 2 && raw.head == '"' && raw.last == '"' then
            unescapeYamlDoubleQuoted(raw.substring(1, raw.length - 1))
          else raw
        Some(key -> value)

  /** Process backslash escapes per YAML double-quoted scalar rules. */
  private def unescapeYamlDoubleQuoted(s: String): String =
    val sb = new StringBuilder(s.length)
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == '\\' && i + 1 < s.length then
        val next = s.charAt(i + 1)
        val toAppend = next match
          case 'n'   => "\n"
          case 't'   => "\t"
          case 'r'   => "\r"
          case '"'   => "\""
          case '\\'  => "\\"
          case other =>
            // Preserve unknown sequences verbatim — caller can deal with them.
            "\\" + other
        val _ = sb.append(toAppend)
        i += 2
      else
        val _ = sb.append(c)
        i += 1
    sb.toString
