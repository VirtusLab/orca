package orca.review

import orca.OrcaFlowException
import orca.discovery.{Origin, TierPrecedence, TierWinner}
import orca.util.{ParsedPrompt, PromptResource, TextUtil}

import java.util.Locale

/** The two tiers a reviewer `.md` file can be discovered in. `BuiltIn` is not
  * one of them: the shipped set is read from the classpath, never from a
  * directory, so it can be shadowed but never discovered.
  */
private[orca] enum ReviewerFileTier:
  case Project, Global

  def origin: Origin = this match
    case ReviewerFileTier.Project => Origin.Project
    case ReviewerFileTier.Global  => Origin.Global

/** One reviewer found on disk: the definition parsed from the winning tier's
  * file, plus every lower-precedence tier defining the same slug — including
  * `BuiltIn` when it overrides a shipped reviewer.
  */
private[orca] case class DiscoveredReviewer(
    reviewer: Reviewer,
    tier: ReviewerFileTier,
    shadows: List[Origin]
)

/** The reviewer definitions a run works from, reachable in a flow body as
  * `reviewerCatalog`. [[all]] and [[minimal]] are the shipped lists with each
  * discovered slug substituted in where it already sits, and the rest appended
  * — both derived from the one `discovered` list, so a catalog cannot describe
  * a reviewer its roster doesn't run. [[ReviewerCatalog.discover]] is the only
  * way to build one with anything in it.
  */
final class ReviewerCatalog private[review] (
    private[orca] val discovered: List[DiscoveredReviewer]
):
  /** Every reviewer this run can pick from, shipped order preserved. */
  lazy val all: List[Reviewer] = withDiscovered(ReviewerPrompts.all)

  /** The small always-applicable subset, plus every added reviewer: a project
    * that ships a reviewer means it for small diffs too, and the picker is what
    * narrows the set per task.
    */
  lazy val minimal: List[Reviewer] = withDiscovered(ReviewerPrompts.minimal)

  /** One line naming each discovered reviewer, its tier, and what it shadows —
    * `None` when the run works from the shipped set alone and there is nothing
    * to report. Names only the discovered entries, not the whole roster.
    */
  private[orca] def describe: Option[String] =
    Option.when(discovered.nonEmpty):
      val entries = discovered.map: d =>
        val shadowed =
          if d.shadows.isEmpty then ""
          else s", shadows ${d.shadows.map(_.label).mkString(", ")}"
        s"${d.reviewer.name} (${d.tier.origin.label}$shadowed)"
      s"discovered reviewers: ${entries.mkString("; ")}"

  private lazy val discoveredBySlug: Map[String, Reviewer] =
    discovered.map(d => d.reviewer.name -> d.reviewer).toMap

  private lazy val added: List[Reviewer] =
    val shipped = ReviewerPrompts.all.map(_.name).toSet
    discovered.map(_.reviewer).filterNot(r => shipped(r.name))

  private def withDiscovered(base: List[Reviewer]): List[Reviewer] =
    base.map(r => discoveredBySlug.getOrElse(r.name, r)) ++ added

object ReviewerCatalog:

  /** The shipped set alone — no tier directory contributed anything. */
  private[orca] val builtIn: ReviewerCatalog = new ReviewerCatalog(Nil)

  /** Read both file tiers and resolve them against the shipped set.
    *
    * Precedence is project > global > built-in, by slug; slugs nothing ships
    * are appended, sorted by name. Slugs are compared lower-cased, the way
    * `SelectedReviewers.pick` resolves the picker's reply — otherwise
    * `Scala-FP.md` would run alongside the shipped `scala-fp` instead of
    * replacing it.
    *
    * A malformed reviewer file aborts the run rather than being skipped: a
    * reviewer silently missing from the roster reads as a clean review. Every
    * bad file is named in one message, so a directory is fixed in one pass.
    *
    * `projectDir` lives under `.orca`, and `os.isDir` follows symlinks — so the
    * caller owes `OrcaDir.assertNoOrcaSymlinks(workDir, projectDir)` before
    * calling, as every other `.orca` read path does. The per-file check below
    * does not cover a symlinked tier directory.
    */
  private[orca] def discover(
      projectDir: os.Path,
      globalDir: os.Path
  ): ReviewerCatalog =
    val scans = List(
      ReviewerFileTier.Project -> scan(projectDir, ReviewerFileTier.Project),
      ReviewerFileTier.Global -> scan(globalDir, ReviewerFileTier.Global)
    )
    val (parseFailures, discovered) = TierPrecedence
      .resolve(scans.map((tier, s) => tier -> s.files))
      .map(discoveredFrom)
      .partitionMap(identity)
    // Both tiers are scanned before anything is raised, so one run names every
    // bad file: a symlink or a collision does not hide the file after it.
    val failures = scans.flatMap(_._2.failures) ++ parseFailures
    if failures.nonEmpty then
      throw new OrcaFlowException(
        "cannot read the reviewers for this run:\n" +
          failures.map(f => s"  - ${f.message}").mkString("\n")
      )
    new ReviewerCatalog(discovered)

  /** The reviewer `winner`'s file defines, recording the tiers it shadows (the
    * shipped set last) — or the failure that file's contents raised.
    */
  private def discoveredFrom(
      winner: TierWinner[ReviewerFileTier, ReviewerFile]
  ): Either[ReviewerPromptFailure, DiscoveredReviewer] =
    val slug = winner.key
    val builtInShadow =
      if ReviewerPrompts.all.exists(_.name == slug) then List(Origin.BuiltIn)
      else Nil
    reviewerFrom(slug, winner.value.parsed, winner.value.path.toString).map:
      reviewer =>
        DiscoveredReviewer(
          reviewer = reviewer,
          tier = winner.tier,
          shadows = winner.shadows.map(_.origin) ++ builtInShadow
        )

  /** One candidate file, read once: its path, for the message a malformed one
    * raises, and its parsed frontmatter and body.
    */
  private case class ReviewerFile(path: os.Path, parsed: ParsedPrompt)

  /** What one tier directory holds: the files that parsed, keyed by slug, and
    * every reason another entry could not be used. Failures are values so
    * [[discover]] reports the whole directory in one message rather than
    * stopping at the first bad entry.
    */
  private case class ReviewerScan(
      failures: List[ReviewerPromptFailure],
      files: Map[String, ReviewerFile]
  )

  /** Names a `.md` file may carry to sit in a reviewer directory without being
    * one. Anything else without frontmatter is an author who forgot the block,
    * not a document — and a reviewer dropped for that reads as a clean review.
    */
  private def isDocument(path: os.Path): Boolean =
    stemOf(path) == "readme" || stemOf(path).startsWith("_")

  /** A file's reviewer slug: its stem, lower-cased the way
    * `SelectedReviewers.pick` resolves the picker's reply.
    */
  private def stemOf(path: os.Path): String =
    path.baseName.toLowerCase(Locale.ROOT)

  /** Scan one tier directory: every reviewer `.md` in it, plus what is wrong
    * with the ones that cannot be used. An absent directory holds nothing.
    *
    * [[isDocument]] names the files that may sit here without being reviewers.
    * Every other `.md` must parse as one: a block the parser can't read, or no
    * block at all, is a broken reviewer and [[reviewerFrom]] reports it.
    *
    * A symlinked `.md` under the PROJECT tier is refused: that directory is
    * committed, and orca runs against arbitrary cloned repos, so reading
    * through a link there would make a file from outside the tree a reviewer's
    * system prompt (ADR 0019's rule). The global tier is the user's own config
    * home, read through links like `settings.properties` beside it — a dotfiles
    * manager that links each file in is normal there; a link with no target
    * surfaces as an unreadable file rather than vanishing from the roster.
    */
  private def scan(dir: os.Path, tier: ReviewerFileTier): ReviewerScan =
    if !os.isDir(dir) then ReviewerScan(Nil, Map.empty)
    else
      val candidates =
        os.list(dir).filter(_.last.endsWith(".md")).filterNot(isDocument)
      // `os.isLink` is lstat/no-follow, so this catches a dangling link too.
      val (linked, plain) =
        if tier == ReviewerFileTier.Project then candidates.partition(os.isLink)
        else (IndexedSeq.empty, candidates)
      val (readFailures, parsed) =
        plain.filterNot(os.isDir).map(read).toList.partitionMap(identity)
      val (collisions, unique) = parsed
        .groupBy(f => stemOf(f.path))
        .partitionMap:
          case (slug, colliding) if colliding.sizeIs > 1 =>
            Left(
              ReviewerPromptFailure.DuplicateSlug(
                slug,
                dir.toString,
                colliding.map(_.path.last).sorted
              )
            )
          case (slug, one) => Right(slug -> one.head)
      ReviewerScan(
        failures =
          linked.map(p => ReviewerPromptFailure.Symlinked(p.toString)).toList
            ++ readFailures ++ collisions.toList,
        files = unique.toMap
      )

  /** Read and parse one candidate. A file that cannot be read at all — a link
    * with no target, a permission error — is a failure, not an absence.
    */
  private def read(
      path: os.Path
  ): Either[ReviewerPromptFailure, ReviewerFile] =
    try
      Right(ReviewerFile(path, PromptResource.parseWithMetadata(os.read(path))))
    catch
      case e: java.io.IOException =>
        Left(
          ReviewerPromptFailure
            .Unreadable(path.toString, TextUtil.throwableMessage(e))
        )
