package orca.shell.cli

import orca.shell.ScanDirs
import orca.shell.actions.SessionAction
import orca.shell.sessions.{ManifestReader, SessionPicker, SessionSelection}

import Cli.{actionFailure, complete, requireTty, usageFailure, withTerminal}

/** `orca continue`'s behavior (ADR 0021 §10/§8): list recorded sessions
  * (`--list`), or resolve a selector to a session and resume it. Table/JSON
  * rendering lives in [[Tables]].
  */
private[cli] object ContinueCli:

  /** `continue`'s full behavior over explicit `dirs`/`tty` (test seam) — tests
    * seed each directory with `.orca/cache/runs/` manifests and simulate either
    * a terminal or a pipe via `tty`. The directories arrive resolved
    * ([[orca.shell.WorktreeScan.dirs]], at the real entry point), so nothing
    * here spawns git.
    */
  private[cli] def runContinue(
      dirs: ScanDirs,
      selector: Option[String],
      list: Boolean,
      json: Boolean,
      tty: Boolean
  ): Int =
    val (runs, warnings) =
      ManifestReader.list(dirs.own, dirs.worktrees, ManifestReader.pidAlive)
    warnings.foreach(Cli.diagnostic)
    if list then
      Tables.printSessionListing(runs, json)
      ExitCodes.Ok
    else
      complete:
        for
          _ <- requireTty("continue", tty).left.map(usageFailure)
          selection <- SessionPicker
            .resolveSelection(runs, selector)
            .left
            .map(actionFailure)
          exit <- resumeSelected(selection)
        yield exit

  /** Prints the resolved session's identity to stderr, then resumes it under a
    * fresh terminal — the harness child's raw exit code propagates on success.
    */
  private def resumeSelected(
      selection: SessionSelection
  ): Either[CliFailure, Int] =
    Cli.diagnostic(resumeNotice(selection))
    withTerminal(SessionAction.resume(_, selection)).left.map(actionFailure)

  /** The resolved session's identity, printed to stderr immediately before
    * [[SessionAction.resume]] execs its harness child (security fold-in, ADR
    * 0021 §10): mirrors what the interactive picker's row label already shows,
    * so a no-selector `orca continue` — which could otherwise resume whatever
    * session a hostile repo's `.orca/cache/runs/` manifest names, without the
    * user ever having chosen it — is visible before the exec, on this tty-gated
    * command's own terminal, giving the user a chance to Ctrl-C.
    */
  private[cli] def resumeNotice(selection: SessionSelection): String =
    SessionAction.identityNotice(
      selection,
      SessionPicker.harnessSettingsName(selection.session.harness)
    )
