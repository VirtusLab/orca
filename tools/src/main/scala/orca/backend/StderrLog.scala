package orca.backend

/** The stderr lines a turn surfaced, kept for its failure messages: a noop
  * listener (tests, simple scripts) would otherwise lose the per-line
  * `TurnEvent.Error`s. Bounded on line count and bytes.
  *
  * @param last
  *   the most recently surfaced line — some CLIs repeat the same warning, and a
  *   run of identical lines surfaces once
  */
private[backend] final case class StderrLog(
    recent: Vector[String],
    last: Option[String]
):
  import StderrLog.*

  /** Whether `line` (stripped and trimmed) is worth an `Error` event. */
  def surfaces(line: String, isNoise: String => Boolean): Boolean =
    line.nonEmpty && !isNoise(line) && !last.contains(line)

  def add(line: String): StderrLog =
    StderrLog(appendBounded(recent, line), Some(line))

  /** Recent lines as a `stderr:` block, or `None` when nothing surfaced. */
  def context: Option[String] =
    Option.when(recent.nonEmpty)(recent.mkString("stderr:\n    ", "\n    ", ""))

private[backend] object StderrLog:

  val empty: StderrLog = StderrLog(Vector.empty, None)

  /** Cap on lines kept — sized for a typical stack trace plus a brief
    * explanation, bounded so a chatty subprocess can't grow memory.
    */
  val MaxLines: Int = 20

  /** Cap on total bytes kept, for the same reason as [[MaxLines]]. */
  val MaxBytes: Int = 4096

  /** Append `line` while respecting both caps, dropping oldest first. A single
    * over-cap line is kept anyway (better than empty diagnostics).
    */
  def appendBounded(buf: Vector[String], line: String): Vector[String] =
    var result = buf :+ line
    while result.size > MaxLines do result = result.tail
    while result.size > 1 && result.map(_.length).sum > MaxBytes do
      result = result.tail
    result
