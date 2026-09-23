package orca

import java.time.Instant

/** The id of an attempt — one process running a flow (`orca run`, one
  * `flow(...)` call): `<startedAt epoch ms>-<pid>`. Names the attempt's
  * manifest, cost log and trace log (`OrcaDir`), and is where the attempt's
  * start time and pid are read from. Ids sort chronologically as strings while
  * the epoch prefix keeps its width, which holds for millisecond epochs until
  * the year 2286.
  */
private[orca] opaque type AttemptId = String

private[orca] object AttemptId:
  private val Spelling = """\d+-\d+""".r

  /** `startedAt` is kept to the millisecond. */
  def apply(startedAt: Instant, pid: Long): AttemptId =
    s"${startedAt.toEpochMilli}-$pid"

  /** The id spelled `s`, or `None` when `s` is not one. */
  def parse(s: String): Option[AttemptId] =
    Option.when(Spelling.matches(s))(s)

  extension (id: AttemptId)
    /** The string form, for file names. */
    def value: String = id

    /** When the attempt started, to the millisecond. */
    def startedAt: Instant =
      Instant.ofEpochMilli(id.takeWhile(_ != '-').toLong)

    /** The process that ran the attempt. */
    def pid: Long = id.dropWhile(_ != '-').drop(1).toLong
