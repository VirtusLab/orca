package orca.shell.actions

import org.jline.terminal.Terminal
import orca.shell.run.ChildTerminal
import orca.shell.sessions.{ResumeCommand, SessionSelection}
import orca.shell.ui.ShellOutput
import orca.subprocess.QuietProc
import orca.tools.pi.PiSessionStore

import java.time.Instant
import scala.util.Try
import scala.util.control.NonFatal

/** Resumes a recorded harness session (ADR 0021 §8): reattaches to a harness's
  * own conversation via its CLI resume flag ([[ResumeCommand]]). Not to be
  * confused with a flow's crash/resume (stage replay from the progress log, ADR
  * 0018 §2.4/§2.5) — that resumes a run, this resumes a chat. The picker that
  * produces `selection` lives in `Main.continueSession`.
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

  /** Pi's transcript dir for the manifest's recorded `wireId` under its
    * `workDir`, or why the chat can't be reattached — the pi counterpart to the
    * gemini index lookup below. Applies [[PiSessionStore.resumable]], the same
    * predicate the backend's own probe uses. Each cause reads back separately,
    * since "pruned" and "the id isn't a directory name" call for different user
    * reactions.
    */
  private[shell] def piSessionDir(
      wireId: String,
      workDir: os.Path
  ): Either[String, os.Path] =
    PiSessionStore
      .dirFor(workDir, wireId)
      .toRight(
        s"the manifest's pi session id `${displayable(wireId)}` is not a directory name"
      )
      .flatMap: dir =>
        if PiSessionStore.resumable(dir, workDir, Instant.now()) then Right(dir)
        else
          Left(
            s"no resumable pi transcript at $dir — pruned by the session-cache " +
              "retention, cleaned with the checkout, recorded under a different " +
              "checkout path, or never written"
          )

  // The id just failed the safe-charset check, so it's arbitrary bytes from an
  // editable file — never interpolated into terminal output raw.
  private def displayable(raw: String): String =
    raw.filterNot(_.isControl).take(80)

  /** Hands [[ResumeCommand.build]] the two live lookups — gemini's index (a
    * `gemini --list-sessions` from the manifest's `workDir`, matched against
    * the wire id) and pi's [[piSessionDir]] — each invoked only by its own
    * harness's branch, then execs the resume command as a tty-inherited child
    * under [[ChildTerminal.withChild]] (ADR 0021 §2) from that same `workDir` —
    * which may differ from the shell's own cwd (claude/gemini/opencode scope
    * session lookup by cwd; pi's transcripts live under it). A failed or
    * missing `gemini` binary is treated the same as "index not found":
    * [[ResumeCommand.build]] reports it as not resumable. [[validatedWorkDir]]
    * guards the manifest's `workDir` up front, and both lookups run under it;
    * the exec itself is further wrapped in a `NonFatal` backstop (e.g. the
    * checkout vanishing in the gap between that check and this exec, or the
    * resume binary itself being missing). Left carries the final "can't
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
        def geminiIndex(uuid: String): Option[Int] =
          try
            val listing =
              QuietProc.call(Seq("gemini", "--list-sessions"), cwd = workDir)
            ResumeCommand.geminiIndexOf(listing.out.text(), uuid)
          catch case NonFatal(_) => None
        ResumeCommand.build(
          selection.session,
          geminiIndex = geminiIndex,
          piSessionDir = piSessionDir(_, workDir)
        ) match
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
