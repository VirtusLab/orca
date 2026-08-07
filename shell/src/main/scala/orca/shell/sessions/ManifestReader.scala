package orca.shell.sessions

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.OrcaDir
import orca.runner.manifest.{ManifestOutcome, RunManifest}

import scala.util.control.NonFatal

/** A manifest paired with whether its run is now known to have crashed (outcome
  * [[ManifestOutcome.Running]] with a dead pid, ADR 0021 §8) — computed once
  * here rather than re-derived by every caller.
  */
private[shell] case class RecordedRun(manifest: RunManifest, crashed: Boolean)

/** Reads `.orca/cache/runs/` for the shell's "continue a session" menu (ADR
  * 0021 §8).
  */
private[shell] object ManifestReader:

  /** Newest-first by `startedAt`. A manifest with [[ManifestOutcome.Running]]
    * whose `pid` is no longer alive is a crashed run — its sessions are still
    * offered, per ADR 0021 §8. Reads `.orca/cache/runs/` passively
    * ([[OrcaDir.runsPath]], not [[OrcaDir.cacheRunsPath]]) — an absent or empty
    * directory yields `(Nil, Nil)` without creating anything on disk. A file
    * that fails to parse as JSON, or doesn't match the `RunManifest` schema —
    * which includes a timestamp that isn't an `Instant` — is skipped with a
    * warning naming the file rather than aborting the whole listing.
    */
  def list(
      workDir: os.Path,
      pidAlive: Long => Boolean
  ): (List[RecordedRun], List[String]) =
    val dir = OrcaDir.runsPath(workDir)
    OrcaDir.assertNoOrcaSymlinks(workDir, dir)
    if !os.exists(dir) then (Nil, Nil)
    else
      val (runs, warnings) =
        os.list(dir)
          .filter(_.ext == "json")
          .toList
          .foldLeft(
            (List.empty[RecordedRun], List.empty[String])
          ):
            case ((runs, warnings), file) =>
              readManifest(file) match
                case Left(warning) => (runs, warning :: warnings)
                case Right(manifest) =>
                  (
                    RecordedRun(manifest, crashed(manifest, pidAlive)) :: runs,
                    warnings
                  )
      (runs.sortBy(_.manifest.startedAt).reverse, warnings.reverse)

  /** An outcome this build doesn't know (a newer build's manifest) is a run
    * that reached some terminal state, not a crashed one — only `Running`
    * paired with a dead pid says the writer never got to finalize.
    */
  private def crashed(
      manifest: RunManifest,
      pidAlive: Long => Boolean
  ): Boolean =
    manifest.outcome match
      case ManifestOutcome.Running => !pidAlive(manifest.pid)
      case ManifestOutcome.Succeeded | ManifestOutcome.Failed |
          ManifestOutcome.Unknown(_) =>
        false

  /** A manifest from any build decodes here: unknown fields are skipped, so
    * only a file missing something this build requires — or unreadable — is
    * refused (ADR 0021 §8 amendment, 2026-08-05).
    *
    * Only the first line of a decode failure is reported. jsoniter appends a
    * multi-line hex dump of the buffer to its message, and `Main`'s loop
    * reprints every warning on each menu redraw, for up to 20 kept manifests —
    * so the untrimmed message paints the menu over. Same trimming, and same
    * reason, as `ProgressStore.parseLog`.
    */
  private def readManifest(file: os.Path): Either[String, RunManifest] =
    try
      Right(readFromString[RunManifest](os.read(file))(using RunManifest.codec))
    catch case NonFatal(e) => Left(s"skipping $file: ${firstLine(e)}")

  private def firstLine(e: Throwable): String =
    Option(e.getMessage)
      .flatMap(_.linesIterator.nextOption())
      .getOrElse(e.getClass.getSimpleName)

  /** The production value of [[list]]'s `pidAlive` parameter (ADR 0021 §8):
    * `ProcessHandle.of` finds nothing for a pid that's been reaped — treated as
    * not alive, same as a live handle reporting `isAlive == false`. Shared by
    * the interactive menu and the CLI's `continue`, so both derive a run's
    * crashed status the same way.
    */
  private[shell] def pidAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)
