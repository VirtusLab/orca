package orca.tools.pi

import orca.OrcaDir
import orca.agents.SessionId

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.util.control.NonFatal

/** Where pi keeps a session's transcripts on disk, and whether one can still be
  * `--continue`d from — the single owner of that layout, of the resumability
  * predicate ([[resumable]]), and of the retention that prunes it
  * ([[Retention]], [[prune]]). [[PiBackend]] points pi at these dirs and probes
  * them through [[resumable]]; the shell's resume command builds its
  * `--session-dir` argv from the same predicate, so the two can't drift.
  */
private[orca] object PiSessionStore:

  private val log = LoggerFactory.getLogger(getClass)

  /** How long an untouched session dir survives in `.orca/cache/pi-sessions`,
    * matching claude's own transcript retention. A pruned session is not a lost
    * turn: [[resumable]] then reports absence and the runtime re-seeds.
    */
  private[orca] val Retention: java.time.Duration =
    java.time.Duration.ofDays(30)

  /** The dir pi writes `id`'s transcripts into, or `None` when `id` is not a
    * usable directory name. Ids orca mints always are; the check is for ids
    * read back from a manifest or progress log, which are files on disk that
    * could have been hand-edited into a path that escapes the store.
    */
  def dirFor(workDir: os.Path, id: String): Option[os.Path] =
    Option.when(SessionId.isSafe(id))(OrcaDir.piSessionsPath(workDir) / id)

  /** Will `pi --session-dir <dir> --continue` pick a transcript up from `dir`,
    * and will it survive the next [[prune]]? Composes the transcript check
    * (some `*.jsonl` whose header pi accepts for `workDir`, see
    * [[headerMatches]]) with the retention cutoff at `now`, so a dir another
    * process is about to prune reports absent rather than after the caller
    * committed to resuming it. Best-effort: a missing or unreadable dir reads
    * as `false`.
    */
  def resumable(dir: os.Path, workDir: os.Path, now: Instant): Boolean =
    hasResumableTranscript(dir, workDir) &&
      touchedSince(dir, now.minus(Retention))

  private def hasResumableTranscript(dir: os.Path, workDir: os.Path): Boolean =
    // At least one matching `*.jsonl`, not exactly one: a first turn that
    // failed after pi seeded the dir is retried fresh and seeds a second file,
    // and `--continue` picks the most recent — the one the retry wrote.
    try
      os.isDir(dir) &&
        os.list
          .stream(dir)
          .exists(f => f.last.endsWith(".jsonl") && headerMatches(f, workDir))
    catch case NonFatal(_) => false

  // Verified against pi's shipped session loader
  // (@earendil-works/pi-coding-agent/dist/core/session-manager.js,
  // `findMostRecentSession`): it reads the file's first 512 bytes, JSON-parses
  // the first line, and requires `type == "session"`, a string `id`, and a
  // non-empty `cwd` that lexically path-resolves equal to pi's process cwd.
  private case class TranscriptHeader(
      `type`: String,
      id: Option[String] = None,
      cwd: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  /** Would pi's `--continue` accept `file` as a transcript for a process run in
    * `workDir`? Any read or parse failure counts as no-match — pi silently
    * starts an empty session for such a file, which orca must report as "not
    * resumable" rather than resume into nothing.
    */
  private def headerMatches(file: os.Path, workDir: os.Path): Boolean =
    try
      val firstLine = new String(
        os.read.bytes(file, offset = 0, count = 512),
        StandardCharsets.UTF_8
      ).takeWhile(_ != '\n')
      val header = readFromString[TranscriptHeader](firstLine)
      header.`type` == "session" && header.id.isDefined &&
      header.cwd.exists(resolvesTo(_, workDir))
    catch case NonFatal(_) => false

  // Lexical resolution like node's `path.resolve` (pi resolves the header cwd
  // against its process cwd, which orca sets to workDir): os.Path normalises
  // `.`/`..` without touching the filesystem.
  private def resolvesTo(cwd: String, workDir: os.Path): Boolean =
    try cwd.nonEmpty && os.Path(os.FilePath(cwd), workDir) == workDir
    catch case NonFatal(_) => false

  /** Delete session dirs untouched since `now` minus [[Retention]]. Pi prunes
    * its own default session store but not the dir orca points it at, so
    * without this the cache grows by one dir per session forever. Run by
    * [[PiBackend.create]] before the backend is handed out, so no probe can
    * call a doomed dir resumable.
    *
    * Fully best-effort — the listing and each candidate are guarded separately
    * — because a cache that can't be pruned (a race with another run, a
    * read-only dir) must not fail the backend it belongs to. Deletes only real
    * directories (never symlinks) with session-id-shaped names, and bails out
    * entirely if any path component down to the root is a symlink — the same
    * guard the write side applies in `OrcaDir.ensurePiSessions`.
    */
  def prune(workDir: os.Path, now: Instant): Unit =
    val root = OrcaDir.piSessionsPath(workDir)
    if os.exists(root) && symlinkFree(workDir, root) then
      val stale = now.minus(Retention)
      try
        os.list(root)
          .foreach: dir =>
            try
              if isPrunableSessionDir(dir) && !touchedSince(dir, stale) then
                os.remove.all(dir)
            catch
              case NonFatal(e) =>
                log.debug(s"skipping unprunable session dir $dir", e)
      catch
        case NonFatal(e) =>
          log.warn(s"could not list pi session cache at $root", e)

  private def symlinkFree(workDir: os.Path, root: os.Path): Boolean =
    try
      OrcaDir.assertNoOrcaSymlinks(workDir, root)
      true
    catch
      case NonFatal(e) =>
        log.warn(s"not pruning pi session cache: ${e.getMessage}")
        false

  // A symlinked or oddly-named entry is left alone: `os.remove.all` on it could
  // reach outside the store.
  private def isPrunableSessionDir(dir: os.Path): Boolean =
    SessionId.isSafe(dir.last) && os.isDir(dir, followLinks = false)

  /** Was `dir` — or any file in it — modified at or after `cutoff`? Its own
    * mtime is not enough: appending to an existing transcript doesn't bump the
    * containing directory's mtime, so a long-lived chat would otherwise look
    * untouched since the turn that created it. Checked dir-first so a fresh dir
    * costs one stat and a stale scan stops at the first fresh file.
    */
  private def touchedSince(dir: os.Path, cutoff: Instant): Boolean =
    def touched(p: os.Path) =
      !Instant.ofEpochMilli(os.mtime(p)).isBefore(cutoff)
    touched(dir) || os.list.stream(dir).exists(touched)
