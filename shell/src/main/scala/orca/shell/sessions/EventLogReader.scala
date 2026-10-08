package orca.shell.sessions

import orca.{AttemptId, OrcaDir}
import orca.runlog.RunEvent
import orca.runner.manifest.AttemptManifest

import java.io.{IOException, UncheckedIOException}
import java.nio.file.NoSuchFileException
import scala.util.control.NonFatal

/** An attempt's projection paired with its id and its [[ObservedStatus]] — read
  * that rather than `manifest.status`, which cannot tell a crashed attempt from
  * a running one.
  */
private[shell] case class RecordedAttempt(
    id: AttemptId,
    manifest: AttemptManifest,
    observedStatus: ObservedStatus
)

/** What [[EventLogReader.list]] found: the continuable attempts, newest first,
  * and one warning per file, attempt or directory it had to skip.
  */
private[shell] case class AttemptListing(
    attempts: List[RecordedAttempt],
    warnings: List[String]
)

/** Reads the runs' event logs, `.orca/cache/runs/<key>/events.jsonl` (ADR
  * 0025), for the shell's "continue a session" menu.
  */
private[shell] object EventLogReader:

  /** Newest-first by `startedAt` across `own` and every directory in
    * `otherWorktrees` — a `--worktree` run keeps its event log in its own tree,
    * so the listing spans the shell's checkout and orca's worktrees of it
    * ([[orca.shell.WorktreeScan.dirs]] picks them). Git is never asked here:
    * the directories arrive resolved, which is what lets these tests seed bare
    * temp directories.
    *
    * `own` is the directory the caller is standing in and is read strictly: a
    * symlinked `.orca` there redirects a read of the user's own tree, and
    * aborting is the signal. The others are read guarded — one warning each —
    * since the shell neither created nor controls them for the length of a
    * redraw (another orca process finishing, a `git worktree remove` in another
    * terminal, a tree left unreadable by a run under a different uid). Two
    * parameters rather than one list, because that is the whole difference
    * between them.
    *
    * A crashed attempt ([[ObservedStatus.Crashed]], decided by `processAlive`;
    * [[ObservedStatus.processAlive]] in production) still has its sessions
    * offered, per ADR 0021 §8. An attempt that committed no session is left
    * out: it has nothing to continue. Each directory's `.orca/cache/runs/` is
    * read passively — absent contributes nothing and creates nothing on disk. A
    * line that does not decode (a torn tail, an unknown event type) is skipped
    * silently; an unreadable event log, and an attempt with no
    * `AttemptStarted`, are skipped with a warning naming the file.
    */
  def list(
      own: os.Path,
      otherWorktrees: List[os.Path],
      processAlive: AttemptManifest => Boolean
  ): AttemptListing =
    val perDir =
      readRunsDir(own, processAlive) ::
        otherWorktrees.map(guarded(_, processAlive))
    AttemptListing(
      perDir.flatMap(_.attempts).sortBy(_.manifest.startedAt).reverse,
      perDir.flatMap(_.warnings)
    )

  private def guarded(
      workDir: os.Path,
      processAlive: AttemptManifest => Boolean
  ): AttemptListing =
    try readRunsDir(workDir, processAlive)
    catch
      case NonFatal(e) =>
        AttemptListing(Nil, List(s"skipping $workDir: ${firstLine(e)}"))

  /** `e`'s class and the first line of its message, for one warning line. */
  private def firstLine(e: Throwable): String =
    val message =
      Option(e.getMessage).flatMap(_.linesIterator.nextOption()).getOrElse("")
    s"${e.getClass.getSimpleName}: $message"

  /** One directory's attempts (the caller sorts) and its warnings. */
  private def readRunsDir(
      workDir: os.Path,
      processAlive: AttemptManifest => Boolean
  ): AttemptListing =
    val dir = OrcaDir.cacheRunsPath(workDir)
    OrcaDir.assertNoOrcaSymlinks(workDir, dir)
    if !os.exists(dir) then AttemptListing(Nil, Nil)
    else
      val perLog = eventLogs(workDir, dir).map(readEventLog(_, processAlive))
      AttemptListing(perLog.flatMap(_.attempts), perLog.flatMap(_.warnings))

  /** The event log of every run under `dir`, refusing a symlinked run directory
    * or log as [[list]] refuses a symlinked `.orca`.
    */
  private def eventLogs(workDir: os.Path, dir: os.Path): List[os.Path] =
    os.list(dir)
      .toList
      .map(_ / OrcaDir.EventLogName)
      .filter: log =>
        OrcaDir.assertNoOrcaSymlinks(workDir, log)
        os.isFile(log)

  private def readEventLog(
      file: os.Path,
      processAlive: AttemptManifest => Boolean
  ): AttemptListing =
    readEvents(file) match
      case Left(warning) => AttemptListing(Nil, List(warning))
      case Right(events) => listAttempts(file, events, processAlive)

  /** Every decodable event in `file`; a file that vanished since it was listed
    * (pruned) has none. Unlike [[orca.runlog.RunEventReader.read]], an
    * unreadable file is a warning: its attempts would otherwise drop out of the
    * listing unnoticed.
    */
  private def readEvents(file: os.Path): Either[String, List[RunEvent]] =
    try Right(os.read.lines.stream(file).flatMap(RunEvent.decodeLine(_)).toList)
    catch
      case _: NoSuchFileException => Right(Nil)
      case e: (IOException | UncheckedIOException) =>
        Left(s"skipping $file: ${firstLine(e)}")

  private def listAttempts(
      file: os.Path,
      events: List[RunEvent],
      processAlive: AttemptManifest => Boolean
  ): AttemptListing =
    val results = events
      .groupBy(_.attempt)
      .toList
      .map: (id, events) =>
        AttemptProjection
          .of(id, events)
          .toRight(s"skipping $file: attempt ${id.value} has no AttemptStarted")
          .map(id -> _)
    AttemptListing(
      results.collect:
        case Right((id, m)) if m.continuable =>
          RecordedAttempt(id, m, ObservedStatus.of(m, processAlive)),
      results.collect { case Left(warning) => warning }
    )
