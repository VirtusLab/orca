package orca.shell.sessions

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.OrcaDir
import orca.runner.RunManifest

import java.time.Instant
import scala.util.Try
import scala.util.control.NonFatal

/** A manifest paired with whether its run is now known to have crashed (outcome
  * `"running"` with a dead pid, ADR 0021 §8) — computed once here rather than
  * re-derived by every caller.
  */
private[orca] case class ReadRun(manifest: RunManifest, crashed: Boolean)

/** Reads `.orca/cache/runs/` for the shell's "continue a session" menu (ADR
  * 0021 §8).
  */
object ManifestReader:

  /** Newest-first by `startedAt`. Skips a file whose `manifestVersion` is newer
    * than this shell understands, or whose `startedAt` doesn't parse as an
    * `Instant` (a hand-edited or corrupt file — the writer always produces a
    * well-formed one), collecting a notice naming the file into the returned
    * warnings rather than guessing at an unknown schema or ordering it by a
    * silent fallback timestamp. A manifest with `outcome: "running"` whose
    * `pid` is no longer alive is a crashed run — its sessions are still
    * offered, per ADR 0021 §8. Reads `.orca/cache/runs/` passively
    * ([[OrcaDir.runsPath]], not [[OrcaDir.cacheRunsPath]]) — an absent or empty
    * directory yields `(Nil, Nil)` without creating anything on disk. A file
    * that fails to parse as JSON, or doesn't match the `RunManifest` schema, is
    * skipped with a warning naming the file rather than aborting the whole
    * listing.
    */
  def list(
      workDir: os.Path,
      pidAlive: Long => Boolean
  ): (List[ReadRun], List[String]) =
    val dir = OrcaDir.runsPath(workDir)
    if !os.exists(dir) then (Nil, Nil)
    else
      val (runs, warnings) =
        os.list(dir)
          .filter(_.ext == "json")
          .toList
          .foldLeft(
            (List.empty[ReadRun], List.empty[String])
          ):
            case ((runs, warnings), file) =>
              readManifest(file) match
                case Left(warning) => (runs, warning :: warnings)
                case Right(manifest) if manifest.manifestVersion > 1 =>
                  (
                    runs,
                    s"skipping $file: manifestVersion ${manifest.manifestVersion} is newer than this shell understands" :: warnings
                  )
                case Right(manifest)
                    if Try(Instant.parse(manifest.startedAt)).isFailure =>
                  (
                    runs,
                    s"skipping $file: unparseable startedAt `${manifest.startedAt}`" :: warnings
                  )
                case Right(manifest) =>
                  val crashed =
                    manifest.outcome == "running" && !pidAlive(manifest.pid)
                  (ReadRun(manifest, crashed) :: runs, warnings)
      (
        runs.sortBy(r => Instant.parse(r.manifest.startedAt)).reverse,
        warnings.reverse
      )

  private def readManifest(file: os.Path): Either[String, RunManifest] =
    try
      Right(readFromString[RunManifest](os.read(file))(using RunManifest.codec))
    catch case NonFatal(e) => Left(s"skipping $file: ${e.getMessage}")
