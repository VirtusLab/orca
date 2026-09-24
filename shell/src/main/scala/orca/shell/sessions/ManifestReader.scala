package orca.shell.sessions

import orca.{AttemptId, OrcaDir}
import orca.runner.manifest.AttemptManifest
import orca.util.JsonFile

import java.time.Duration
import scala.util.control.NonFatal

/** A manifest paired with its attempt's id (from the file name) and its
  * [[ObservedStatus]] — read that rather than `manifest.status`, which cannot
  * tell a crashed attempt from a running one.
  */
private[shell] case class RecordedAttempt(
    id: AttemptId,
    manifest: AttemptManifest,
    observedStatus: ObservedStatus
)

/** What [[ManifestReader.list]] found: the continuable attempts, newest first,
  * and one warning per file or directory it had to skip.
  */
private[shell] case class AttemptListing(
    attempts: List[RecordedAttempt],
    warnings: List[String]
)

/** Reads `.orca/cache/attempts/` for the shell's "continue a session" menu (ADR
  * 0021 §8).
  */
private[shell] object ManifestReader:

  /** Newest-first by `startedAt` across `own` and every directory in
    * `otherWorktrees` — a `--worktree` run keeps its manifests in its own tree,
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
    * A crashed attempt ([[ObservedStatus.Crashed]], decided by `processAlive`)
    * still has its sessions offered, per ADR 0021 §8. An attempt that committed
    * no session is left out: it has nothing to continue. Each directory's
    * `.orca/cache/attempts/` is read passively ([[OrcaDir.attemptsPath]], not
    * [[OrcaDir.ensureAttempts]]) — absent or empty contributes nothing and
    * creates nothing on disk. A file that fails to parse as JSON, or doesn't
    * match the `AttemptManifest` schema — which includes a timestamp that isn't
    * an `Instant` — is skipped with a warning naming the file rather than
    * aborting the whole listing.
    */
  def list(
      own: os.Path,
      otherWorktrees: List[os.Path],
      processAlive: AttemptManifest => Boolean
  ): AttemptListing =
    val perDir =
      readAttemptsDir(own, processAlive) ::
        otherWorktrees.map(guarded(_, processAlive))
    AttemptListing(
      perDir.flatMap(_.attempts).sortBy(_.manifest.startedAt).reverse,
      perDir.flatMap(_.warnings)
    )

  private def guarded(
      workDir: os.Path,
      processAlive: AttemptManifest => Boolean
  ): AttemptListing =
    try readAttemptsDir(workDir, processAlive)
    catch
      case NonFatal(e) =>
        AttemptListing(Nil, List(s"skipping $workDir: ${firstLine(e)}"))

  /** `e`'s class and the first line of its message, for one warning line. */
  private def firstLine(e: Throwable): String =
    val message =
      Option(e.getMessage).flatMap(_.linesIterator.nextOption()).getOrElse("")
    s"${e.getClass.getSimpleName}: $message"

  /** One directory's manifests (the caller sorts) and its warnings, both in
    * file order.
    */
  private def readAttemptsDir(
      workDir: os.Path,
      processAlive: AttemptManifest => Boolean
  ): AttemptListing =
    val dir = OrcaDir.attemptsPath(workDir)
    OrcaDir.assertNoOrcaSymlinks(workDir, dir)
    if !os.exists(dir) then AttemptListing(Nil, Nil)
    else
      val results =
        os.list(dir).filter(OrcaDir.isManifest).toList.map(readManifest)
      val attempts = results.collect:
        case Right((id, m)) if m.continuable =>
          RecordedAttempt(id, m, ObservedStatus.of(m, processAlive))
      AttemptListing(
        attempts,
        results.collect { case Left(warning) => warning }
      )

  /** A missing file is a warning too: the listing saw it and the read found
    * nothing, which for a directory the shell does not control is a race with
    * pruning or removal worth a line.
    */
  private def readManifest(
      file: os.Path
  ): Either[String, (AttemptId, AttemptManifest)] =
    OrcaDir.attemptIdOf(file) match
      case None     => Left(s"skipping $file: not named after an attempt id")
      case Some(id) => readManifestBody(file).map(id -> _)

  private def readManifestBody(
      file: os.Path
  ): Either[String, AttemptManifest] =
    JsonFile.read[AttemptManifest](file) match
      case JsonFile.Read.Loaded(manifest) => Right(manifest)
      case JsonFile.Read.Absent           => Left(s"skipping $file: vanished")
      case JsonFile.Read.Unreadable(reason) =>
        Left(s"skipping $file: $reason")
      case JsonFile.Read.Corrupt(reason) => Left(s"skipping $file: $reason")

  /** The production value of [[list]]'s `processAlive` parameter (ADR 0021 §8),
    * shared by the interactive menu and the CLI's `continue`. `pid` must name a
    * live process that started no later than `startedAt` (which the attempt
    * takes inside that process) — a later start means the pid was reused. The
    * slack absorbs wall-clock steps, which shift the start instants the OS
    * reports; a crashed attempt's pid being reused within it is negligible. An
    * unknown start instant counts as alive.
    */
  private[shell] def processAlive(manifest: AttemptManifest): Boolean =
    ProcessHandle
      .of(manifest.pid)
      .filter(_.isAlive)
      .map[Boolean]: handle =>
        handle
          .info()
          .startInstant()
          .map[Boolean](!_.isAfter(manifest.startedAt.plus(StartSlack)))
          .orElse(true)
      .orElse(false)

  private val StartSlack = Duration.ofMinutes(1)
