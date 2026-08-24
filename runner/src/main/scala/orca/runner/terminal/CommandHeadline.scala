package orca.runner.terminal

/** One-line headline for a shell command: drops the `sh -c "…"` wrapper some
  * agent harnesses add around a command, then cuts on a token boundary so
  * what's left reads as shell rather than stopping inside a quoted argument.
  */
private[terminal] object CommandHeadline:

  /** `/bin/bash -lc `, `bash -c `, `sh -c ` and friends — identical on every
    * call, so it says nothing.
    */
  private val ShellWrapper: java.util.regex.Pattern =
    java.util.regex.Pattern
      .compile("^(?:\\S*/)?(?:ba|z|da|k)?sh\\s+-[a-z]*c\\s+")

  /** Divides `maxLength` to fix how early a token boundary may cut: at 2 at
    * least half the width survives, and a single token longer than that is
    * truncated mid-token instead.
    */
  private val MinBoundaryDivisor: Int = 2

  def render(command: String, maxLength: Int): String =
    // Collapsed first: a `git commit -m "subject\n\nbody"` arrives carrying real
    // newlines, and both the wrapper match and the width budget assume one line.
    val single = Text.collapseWhitespace(command)
    val matcher = ShellWrapper.matcher(single)
    val unwrapped =
      if matcher.lookingAt() then unquote(single.substring(matcher.end()))
      else single
    truncateAtBoundary(unwrapped, maxLength)

  /** Drop the quotes the wrapper needed to pass the command as one argument.
    * Deciding whether the outer pair really matches would take a shell lexer:
    * the commands this fires on toggle quoting mid-word (`-g '"'!*.pyc'"'`), so
    * an interior quote is normal and proves nothing. Matching first against
    * last is what reads correctly on those; a command the wrapper passed as two
    * quoted words instead loses the boundary between them, which is a wrong
    * headline for a shape orca's own backends never produce.
    */
  private def unquote(s: String): String =
    val quoted = s.length >= 2 && (s.head == '"' || s.head == '\'') &&
      s.last == s.head
    if quoted then s.substring(1, s.length - 1) else s

  private def truncateAtBoundary(s: String, maxLength: Int): String =
    if s.length <= maxLength then s
    else
      val window = s.take(maxLength - 1)
      val boundary = window.lastIndexWhere(_.isWhitespace)
      if boundary < maxLength / MinBoundaryDivisor then
        Text.truncate(s, maxLength)
      else s"${window.take(boundary)}…"
