package orca.shell.cli

import orca.shell.actions.SessionAction
import orca.shell.sessions.{ManifestReader, SessionPicker, SessionSelection}

import Cli.{actionFailure, complete, requireTty, usageFailure, withTerminal}

/** `orca continue`'s behavior (ADR 0021 §10/§8): list recorded sessions
  * (`--list`), or resolve a selector to a session and resume it. Table/JSON
  * rendering lives in [[Tables]].
  */
private[cli] object ContinueCli:

  /** `continue`'s full behavior over an explicit `workDir`/`tty` — pulled out
    * of the `@main` method so tests can point it at a temp project (seeded with
    * `.orca/cache/runs/` manifests) and simulate either a terminal or a pipe
    * without touching the real console.
    */
  private[cli] def runContinue(
      workDir: os.Path,
      selector: Option[String],
      list: Boolean,
      json: Boolean,
      tty: Boolean
  ): Int =
    val (runs, warnings) = ManifestReader.list(workDir, ManifestReader.pidAlive)
    warnings.foreach(Cli.fail)
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
    Cli.fail(resumeNotice(selection))
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
