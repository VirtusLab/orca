package orca.util

/** Small cross-package text helpers that don't belong to any one feature. */
private[orca] object TextUtil:

  /** A throwable's human message: its `getMessage` (or the class name when
    * blank), optionally collapsed to its first line.
    */
  def throwableMessage(e: Throwable, firstLineOnly: Boolean = false): String =
    val msg = Option(e.getMessage).filter(_.nonEmpty)
    val picked =
      if firstLineOnly then msg.flatMap(_.linesIterator.nextOption()) else msg
    picked.getOrElse(e.getClass.getName)

  /** Render `n` with an English noun, appending "s" when `n != 1` (`"1 review
    * comment"` / `"3 review comments"`).
    */
  def pluralize(n: Int, singular: String): String =
    s"$n $singular${if n == 1 then "" else "s"}"

  /** Collapse every whitespace run (including newlines) to a single space. */
  def collapseWhitespace(s: String): String = s.replaceAll("""\s+""", " ")

  /** One-line preview of text that will be printed to a terminal: stripped,
    * whitespace collapsed, control characters dropped, and clipped to `maxLen`
    * with a trailing `…` only when clipping actually happened.
    *
    * Used for text read back from a progress-log header — committed,
    * hand-editable content, so a stray escape byte would otherwise corrupt the
    * menu row or error message it lands in — and to keep a long task from
    * burying the text around it.
    */
  def onelinePreview(text: String, maxLen: Int): String =
    val flattened = collapseWhitespace(text.strip()).filterNot(_.isControl)
    if flattened.length > maxLen then s"${flattened.take(maxLen)}…"
    else flattened

  /** Collapse each newline run (with adjacent whitespace) to a single space,
    * leaving other whitespace intact. Enforces the settings-file
    * one-physical-line contract for command lines, so the executed command and
    * the written `key = command` line stay identical.
    */
  def collapseNewlines(s: String): String = s.replaceAll("""\s*\R\s*""", " ")
