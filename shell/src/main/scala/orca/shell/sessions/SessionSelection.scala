package orca.shell.sessions

import orca.AttemptId
import orca.runner.manifest.{ManifestSession, AttemptManifest}

/** A recorded session's id: its attempt, and its 1-based position in that
  * attempt's manifest `sessions`. It stays valid while other flows run, since a
  * manifest only appends sessions and updates them in place. Spelled `<attempt
  * id>:<position>`, e.g. `1758612345678-4242:2`.
  */
private[shell] case class SessionRef(attempt: AttemptId, position: Int):
  def spelling: String = s"${attempt.value}:$position"

private[shell] object SessionRef:
  private val Spelling = """(\d+-\d+):(\d+)""".r

  /** The ref spelled `s`, or `None` when `s` is not one. */
  def parse(s: String): Option[SessionRef] =
    s match
      case Spelling(attempt, position) =>
        for
          id <- AttemptId.parse(attempt)
          n <- position.toIntOption.filter(_ >= 1)
        yield SessionRef(id, n)
      case _ => None

/** One recorded session with the attempt manifest it came from — everything
  * [[orca.shell.actions.SessionAction.resume]] needs (the harness command comes
  * from the session; the working directory comes from the manifest, which may
  * differ from the shell's own cwd). `crashed` carries the attempt's crashed
  * status (status `"Running"` with a dead pid) through to display — resuming
  * still offers a crashed attempt's sessions (ADR 0021 §8), but the notice
  * should say so.
  *
  * Lives in `sessions` so [[SessionPicker]] can construct it without `sessions`
  * depending back on `actions`, which — with `actions/SessionAction` already
  * reaching into `sessions/ResumeCommand` — would otherwise be a package cycle.
  */
private[shell] case class SessionSelection(
    ref: SessionRef,
    manifest: AttemptManifest,
    session: ManifestSession,
    crashed: Boolean
)
