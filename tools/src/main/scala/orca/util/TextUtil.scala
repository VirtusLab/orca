package orca.util

/** Small cross-package text helpers that don't belong to any one feature — kept
  * here (alongside [[TextWrap]]) so callers across the flow/runner/backend
  * packages share one implementation rather than re-deriving these one-liners.
  */
private[orca] object TextUtil:

  /** A throwable's human message: its `getMessage` (or the class name when
    * blank), optionally collapsed to its first line. Used by `stage` (first
    * line, for a tidy one-line `✖`) and the flow boundary (whole message, so
    * multi-line diagnostics like opencode's start-failure stderr survive).
    */
  def throwableMessage(e: Throwable, firstLineOnly: Boolean = false): String =
    val msg = Option(e.getMessage).filter(_.nonEmpty)
    val picked =
      if firstLineOnly then msg.flatMap(_.linesIterator.nextOption()) else msg
    picked.getOrElse(e.getClass.getName)

  /** Pluralize an English noun by appending "s" when `n != 1`. The same count
    * goes into the rendered string (`"1 review comment"` / `"3 review
    * comments"`), so this also encodes the count. Centralised here so callers
    * across packages produce consistent wording.
    */
  def pluralize(n: Int, singular: String): String =
    s"$n $singular${if n == 1 then "" else "s"}"

  /** Collapse every whitespace run (including newlines) to a single space — the
    * one-physical-line guard shared by the settings-file renderer and the
    * discovery narration.
    */
  def collapseWhitespace(s: String): String = s.replaceAll("""\s+""", " ")

  /** Collapse each newline run (with adjacent whitespace) to a single space,
    * leaving other whitespace intact — the settings-file one-physical-line
    * contract for command lines. Shared by the renderer (writing `key =
    * command` lines) and discovery assembly (the commands the first run
    * executes), so the executed command and the written line are identical.
    */
  def collapseNewlines(s: String): String = s.replaceAll("""\s*\R\s*""", " ")
