package orca

import java.time.Instant

/** The id of an attempt — one process running a flow (`orca run`, one
  * `flow(...)` call): `<startedAt epoch ms>-<pid>`. Names the attempt's
  * manifest and cost log (`OrcaDir`). Ids sort chronologically as strings while
  * the epoch prefix keeps its width, which holds for millisecond epochs until
  * the year 2286.
  */
private[orca] opaque type AttemptId = String

private[orca] object AttemptId:
  private val Spelling = """\d+-\d+""".r

  def apply(startedAt: Instant, pid: Long): AttemptId =
    s"${startedAt.toEpochMilli}-$pid"

  /** The id spelled `s`, or `None` when `s` is not one. */
  def parse(s: String): Option[AttemptId] =
    Option.when(Spelling.matches(s))(s)

  extension (id: AttemptId)
    /** The string form, for file names. */
    def value: String = id
