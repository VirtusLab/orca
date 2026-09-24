package orca.shell.sessions

import orca.runner.manifest.{ManifestSession, AttemptManifest}

/** One recorded session with the attempt manifest it came from — everything
  * [[orca.shell.actions.SessionAction.resume]] needs (the harness command comes
  * from the session; the working directory comes from the manifest, which may
  * differ from the shell's own cwd). `observedStatus` is the attempt's, carried
  * through to display — resuming still offers a crashed attempt's sessions (ADR
  * 0021 §8), but the notice should say so.
  *
  * Lives in `sessions` so [[SessionIndex]] can construct it without `sessions`
  * depending back on `actions`, which — with `actions/SessionAction` already
  * reaching into `sessions/ResumeCommand` — would otherwise be a package cycle.
  */
private[shell] case class SessionSelection(
    ref: SessionRef,
    manifest: AttemptManifest,
    session: ManifestSession,
    observedStatus: ObservedStatus
)
