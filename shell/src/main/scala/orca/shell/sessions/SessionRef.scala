package orca.shell.sessions

import orca.AttemptId

/** A recorded session's id: its attempt, and its 1-based position in that
  * attempt's sessions, in first-commit order. It stays valid while other flows
  * run, since the event log is append-only. Spelled `<attempt id>:<position>`,
  * e.g. `1758612345678-4242:2`.
  */
private[shell] case class SessionRef(attempt: AttemptId, position: Int):
  def spelling: String = s"${attempt.value}:$position"

private[shell] object SessionRef:
  private val Spelling = """(.+):(\d+)""".r

  /** The ref spelled `s`, or `None` when `s` is not one. */
  def parse(s: String): Option[SessionRef] =
    s match
      case Spelling(attempt, position) =>
        for
          id <- AttemptId.parse(attempt)
          n <- position.toIntOption.filter(_ >= 1)
        yield SessionRef(id, n)
      case _ => None
