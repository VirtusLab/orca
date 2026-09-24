package orca.shell.cli

import orca.runner.manifest.AttemptManifest
import orca.shell.ScanDirs
import orca.shell.actions.SessionAction
import orca.shell.sessions.{
  AttemptListing,
  ManifestReader,
  SessionIndex,
  SessionSelection
}

import Cli.{actionFailure, complete, requireTty, usageFailure, withTerminal}

/** `orca continue`'s behavior (ADR 0021 §10/§8): list recorded sessions
  * (`--list`), or resolve a selector to a session and resume it. Table/JSON
  * rendering lives in [[Tables]].
  */
private[cli] object ContinueCli:

  /** `continue`'s full behavior over explicit `dirs`/`tty`/`processAlive` (test
    * seam) — tests seed each directory with `.orca/cache/attempts/` manifests,
    * simulate a terminal or a pipe via `tty`, and fake process liveness via
    * `processAlive`. The directories arrive resolved
    * ([[orca.shell.WorktreeScan.dirs]], at the real entry point), so nothing
    * here spawns git.
    */
  private[cli] def runContinue(
      dirs: ScanDirs,
      selector: Option[String],
      list: Boolean,
      json: Boolean,
      tty: Boolean,
      processAlive: AttemptManifest => Boolean
  ): Int =
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(dirs.own, dirs.worktrees, processAlive)
    warnings.foreach(Cli.diagnostic)
    val index = SessionIndex.of(attempts)
    if list then
      Tables.printSessionListing(index, json)
      ExitCodes.Ok
    else
      complete:
        for
          _ <- requireTty("continue", tty).left.map(usageFailure)
          selection <- index.resolve(selector).left.map(actionFailure)
          exit <- resumeSelected(selection)
        yield exit

  /** Prints the resolved session's identity to stderr, then resumes it under a
    * fresh terminal — the harness child's raw exit code propagates on success.
    */
  private def resumeSelected(
      selection: SessionSelection
  ): Either[CliFailure, Int] =
    Cli.diagnostic(SessionAction.resumeNotice(selection))
    withTerminal(SessionAction.resume(_, selection)).left.map(actionFailure)
