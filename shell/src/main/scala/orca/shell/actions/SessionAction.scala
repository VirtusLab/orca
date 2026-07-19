package orca.shell.actions

import org.jline.terminal.Terminal
import orca.agents.BackendTag
import orca.runner.{ManifestSession, RunManifest}
import orca.shell.run.ChildTerminal
import orca.shell.sessions.ResumeCommand
import orca.shell.ui.ShellOutput
import orca.subprocess.QuietProc

import scala.util.Try
import scala.util.control.NonFatal

/** One prior run's manifest paired with the session picked to resume —
  * everything [[SessionAction.resume]] needs (the harness command comes from
  * the session; the working directory comes from the manifest, which may differ
  * from the shell's own cwd). `crashed` carries the run's crashed status
  * (outcome `"running"` with a dead pid) through to display — resuming still
  * offers a crashed run's sessions (ADR 0021 §8), but the notice should say so.
  */
private[shell] case class SessionSelection(
    manifest: RunManifest,
    session: ManifestSession,
    crashed: Boolean
)

/** Resumes a recorded harness session (ADR 0021 §8) — the moved action half of
  * `Main.continueSession`/`resumeSession`; the picker that produces `selection`
  * stays in `Main`.
  */
private[shell] object SessionAction:

  /** The resolved session's identity — name, harness, stage, crashed status,
    * and `workDir` — for display immediately before [[resume]] execs its
    * harness child (ADR 0021 §10). Shared by the CLI's tty-gated pre-exec
    * notice and the interactive picker, so both show `workDir` before resuming
    * rather than only the CLI path. `harnessName` is the caller's
    * already-resolved settings-file harness name (`claude`, `codex`, …), not
    * the manifest's wire name.
    */
  def identityNotice(selection: SessionSelection, harnessName: String): String =
    val session = selection.session
    val name = session.sessionName.getOrElse(session.agent)
    val stage = session.stage.fold("")(s => s", stage '$s'")
    val crashedSuffix = if selection.crashed then " (crashed)" else ""
    s"resuming session '$name' [$harnessName]$stage, in ${selection.manifest.workDir}$crashedSuffix"

  /** Parses the manifest's stored `workDir` and confirms it's still a directory
    * — a checkout deleted after its run finished otherwise crashes resume:
    * `os.Path` throws `IllegalArgumentException` on a relative or malformed
    * string, and `os.proc`'s `cwd` throws `IOException` on a well-formed but
    * now-missing directory. Both collapse to the one message here rather than
    * propagating past the caller.
    */
  private[shell] def validatedWorkDir(raw: String): Either[String, os.Path] =
    val resolved =
      try Some(os.Path(raw)).filter(p => Try(os.isDir(p)).getOrElse(false))
      catch case NonFatal(_) => None
    resolved.toRight(s"the recorded working directory $raw no longer exists")

  /** Resolves gemini's index (a live `gemini --list-sessions` from the
    * manifest's `workDir`, matching the session's stored wireId) if needed,
    * then execs the resume command as a tty-inherited child under
    * [[ChildTerminal.withChild]] (ADR 0021 §2) from that same `workDir` — which
    * may differ from the shell's own cwd (claude/gemini/opencode scope session
    * lookup by cwd). A failed or missing `gemini` binary is treated the same as
    * "index not found": [[ResumeCommand.build]] reports it as not resumable.
    * [[validatedWorkDir]] guards the manifest's `workDir` up front; the exec
    * itself is further wrapped in a `NonFatal` backstop (e.g. the checkout
    * vanishing in the gap between that check and this exec, or the resume
    * binary itself being missing). Left carries the final "can't
    * resume"/"resume failed" message without ever spawning a process on the
    * former; Right carries the resumed child's exit code.
    */
  def resume(
      terminal: Terminal,
      selection: SessionSelection
  ): Either[String, Int] =
    validatedWorkDir(selection.manifest.workDir) match
      case Left(reason) => Left(s"can't resume — $reason")
      case Right(workDir) =>
        val isGemini =
          BackendTag
            .fromWireName(selection.session.harness)
            .contains(BackendTag.Gemini)
        val geminiIndex =
          if !isGemini then None
          else
            selection.session.wireId.flatMap: uuid =>
              try
                val listing = QuietProc
                  .call(Seq("gemini", "--list-sessions"), cwd = workDir)
                ResumeCommand.geminiIndexOf(listing.out.text(), uuid)
              catch case NonFatal(_) => None
        ResumeCommand.build(selection.session, geminiIndex) match
          case Left(reason) => Left(s"can't resume — $reason")
          case Right(argv) =>
            try
              val exitCode = ChildTerminal.withChild(terminal):
                os.proc(argv)
                  .call(
                    cwd = workDir,
                    stdin = os.Inherit,
                    stdout = os.Inherit,
                    stderr = os.Inherit,
                    check = false
                  )
                  .exitCode
              ShellOutput.info(s"session ended (exit code $exitCode)")
              Right(exitCode)
            catch case NonFatal(e) => Left(s"resume failed — ${e.getMessage}")
