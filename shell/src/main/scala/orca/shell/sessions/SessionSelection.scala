package orca.shell.sessions

import orca.runner.manifest.{ManifestSession, AttemptManifest}

/** One prior attempt's manifest paired with the session picked to resume —
  * everything [[orca.shell.actions.SessionAction.resume]] needs (the harness
  * command comes from the session; the working directory comes from the
  * manifest, which may differ from the shell's own cwd). `crashed` carries the
  * attempt's crashed status (status `"Running"` with a dead pid) through to
  * display — resuming still offers a crashed attempt's sessions (ADR 0021 §8),
  * but the notice should say so.
  *
  * Lives in `sessions` so [[SessionPicker]] can construct it without `sessions`
  * depending back on `actions`, which — with `actions/SessionAction` already
  * reaching into `sessions/ResumeCommand` — would otherwise be a package cycle.
  */
private[shell] case class SessionSelection(
    manifest: AttemptManifest,
    session: ManifestSession,
    crashed: Boolean
)
