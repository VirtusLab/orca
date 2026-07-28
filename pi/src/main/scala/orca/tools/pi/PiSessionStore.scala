package orca.tools.pi

import orca.OrcaDir
import orca.agents.SessionId

import scala.util.control.NonFatal

/** Where pi keeps a session's transcripts on disk, and whether one is still
  * there to `--continue` from — the single owner of that layout. [[PiBackend]]
  * points pi at these dirs and probes them (layering its retention cutoff on
  * top); the shell's resume command builds its `--session-dir` argv from the
  * same answer, so the two can't drift.
  */
private[orca] object PiSessionStore:

  /** The dir pi writes `id`'s transcripts into, or `None` when `id` is not a
    * usable directory name. Ids orca mints always are; the check is for ids
    * read back from a manifest or progress log, which are files on disk that
    * could have been hand-edited into a path that escapes the store.
    */
  def dirFor(workDir: os.Path, id: String): Option[os.Path] =
    Option.when(SessionId.isSafe(id))(OrcaDir.piSessionsPath(workDir) / id)

  /** Does `dir` hold a transcript to resume from? At least one `*.jsonl`, not
    * exactly one: a first turn that failed after pi seeded the dir is retried
    * fresh and seeds a second file, and `--continue` picks the most recent —
    * the one the retry wrote. A missing or unreadable dir reads as `false`.
    */
  def hasTranscript(dir: os.Path): Boolean =
    try os.isDir(dir) && os.list.stream(dir).exists(_.last.endsWith(".jsonl"))
    catch case NonFatal(_) => false
