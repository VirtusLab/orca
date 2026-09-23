package orca.tools

/** Parses what `git diff` prints in its `--numstat -z` and `--numstat -z -p`
  * modes.
  */
private[tools] object GitDiffOutput:

  /** The record separator git's `-z` output modes use. */
  private val NUL: Char = '\u0000'

  /** The line git starts each file's part of a patch with, at column 0. No
    * content line can imitate it: every line inside a hunk carries a `
    * `/`+`/`-`/`\` prefix.
    */
  private val FileHeader: String = "diff --git "

  /** The records of a `git diff --numstat -z`, in git's order.
    *
    * A record is `<added>\t<deleted>\t<path>`, NUL-terminated. A rename ends
    * the record after the tabs and follows it with the old and the new path as
    * two more NUL-terminated fields; the new one is the path the change now
    * lives at, which is what [[GitTool.changedFiles]] reports. A `-` in place
    * of a count marks a binary file, which git reports as differing without
    * saying by how much.
    *
    * The path is everything after the second tab, splitting no further: `-z`
    * turns off the quoting git would otherwise apply to a tab in a name, so a
    * tabbed path arrives raw and would otherwise look like extra fields.
    *
    * Anything that doesn't parse as a record is skipped rather than failing the
    * call: a file list is worth having even if one entry of it is unreadable.
    */
  def parseNumstat(raw: String): List[ChangedFile] =
    @scala.annotation.tailrec
    def loop(
        fields: List[String],
        acc: List[ChangedFile]
    ): List[ChangedFile] =
      fields match
        case Nil            => acc.reverse
        case record :: rest =>
          // The limit stops the split at the path, and keeps the empty third
          // field a rename's record ends with — which is what tells the two
          // shapes apart, since git never names an empty path.
          record.split("\t", 3).toList match
            // Rename: the paths are the next two records, old then new.
            case added :: deleted :: "" :: Nil =>
              rest match
                case _ :: renamedTo :: tail =>
                  loop(
                    tail,
                    ChangedFile(renamedTo, change(added, deleted)) :: acc
                  )
                case _ => loop(rest, acc)
            case added :: deleted :: path :: Nil =>
              loop(rest, ChangedFile(path, change(added, deleted)) :: acc)
            case _ => loop(rest, acc)
    loop(raw.split(NUL).toList.filter(_.nonEmpty), Nil)

  private def change(added: String, deleted: String): FileChange =
    (added.toIntOption, deleted.toIntOption) match
      case (Some(a), Some(d)) => FileChange.Lines(a, d)
      case _                  => FileChange.Binary

  /** A `git diff --numstat -z -p` answer split into its numstat records and its
    * patch, `None` when it holds no complete record list — no changes, or a
    * list the read cap cut. Git ends the records with an empty one; no record
    * contains `\0\0`, since a path is never empty.
    */
  def splitNumstatPatch(raw: String): Option[(String, String)] =
    raw.indexOf(s"$NUL$NUL") match
      case -1 => None
      case at => Some((raw.take(at + 1), raw.drop(at + 2)))

  /** Whether a read holds all of git's output. */
  enum Read:
    case Whole, Cut

  /** Each of `files`' own part of `patch`, the patch of the same `git diff`
    * call, which lists them in the same order. A [[Read.Cut]] patch ends in a
    * partial part, which is dropped, and pairs only a prefix of `files`.
    *
    * Empty when the parts don't line up with `files`: a file is then never
    * shown on the strength of another file's text.
    */
  def pairSections(
      files: List[ChangedFile],
      patch: String,
      read: Read
  ): Map[String, String] =
    val parts = fileParts(patch)
    val (whole, lineUp) = read match
      case Read.Whole => (parts, parts.size == files.size)
      case Read.Cut   => (parts.dropRight(1), parts.size - 1 <= files.size)
    if lineUp then files.map(_.path).zip(whole).toMap else Map.empty

  /** `patch` split into one part per [[FileHeader]] line. A type change (a file
    * replaced by a symlink) is written as a deletion and an addition under the
    * same header line; the two are one part.
    */
  // `d` makes `^` match only after `\n`: by default it also matches after a
  // lone `\r`, which a content line can carry.
  private def fileParts(patch: String): List[String] =
    patch
      .split(s"(?md)(?=^$FileHeader)")
      .toList
      .filter(_.nonEmpty)
      .foldRight(List.empty[String]):
        case (part, next :: rest) if headerOf(part) == headerOf(next) =>
          (part + next) :: rest
        case (part, acc) => part :: acc

  private def headerOf(part: String): String = part.takeWhile(_ != '\n')
