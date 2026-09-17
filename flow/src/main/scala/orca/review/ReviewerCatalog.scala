package orca.review

import orca.OrcaFlowException
import orca.util.{ParsedPrompt, PromptResource}

import java.util.Locale

/** Which tier a reviewer in the resolved roster came from. */
private[orca] enum ReviewerOrigin:
  case Project, Global, BuiltIn

private[orca] object ReviewerOrigin:
  /** The tier's user-facing label, as shown in the step that names what was
    * discovered.
    */
  extension (origin: ReviewerOrigin)
    def label: String = origin match
      case ReviewerOrigin.Project => "project"
      case ReviewerOrigin.Global  => "global"
      case ReviewerOrigin.BuiltIn => "built-in"

/** The two tiers a reviewer `.md` file can be discovered in. `BuiltIn` is not
  * one of them: the shipped set is read from the classpath, never from a
  * directory, so it can be shadowed but never discovered.
  */
private[orca] enum ReviewerFileTier:
  case Project, Global

private[orca] object ReviewerFileTier:
  extension (tier: ReviewerFileTier)
    def origin: ReviewerOrigin = tier match
      case ReviewerFileTier.Project => ReviewerOrigin.Project
      case ReviewerFileTier.Global  => ReviewerOrigin.Global

/** One reviewer found on disk: the definition parsed from the winning tier's
  * file, plus every lower-precedence tier defining the same slug — including
  * `BuiltIn` when it overrides a shipped reviewer.
  */
private[orca] case class DiscoveredReviewer(
    reviewer: Reviewer,
    tier: ReviewerFileTier,
    shadows: List[ReviewerOrigin]
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
    val byTier = List(
      ReviewerFileTier.Project -> reviewerFiles(
        projectDir,
        ReviewerFileTier.Project
      ),
      ReviewerFileTier.Global -> reviewerFiles(
        globalDir,
        ReviewerFileTier.Global
      )
    )
    val (failures, discovered) = byTier
      .flatMap(_._2.keySet)
      .distinct
      .sorted
      .map(resolve(_, byTier))
      .partitionMap(identity)
    if failures.nonEmpty then
      throw new OrcaFlowException(
        "cannot read the reviewers for this run:\n" +
          failures.map(f => s"  - ${f.message}").mkString("\n")
      )
    new ReviewerCatalog(discovered)

  /** The tiers defining `slug`, winner first, with the shipped set appended as
    * the last thing a file tier can shadow.
    */
  private def resolve(
      slug: String,
      byTier: List[(ReviewerFileTier, Map[String, ReviewerFile])]
  ): Either[ReviewerPromptFailure, DiscoveredReviewer] =
    val hits = byTier.collect:
      case (tier, files) if files.contains(slug) => tier -> files(slug)
    val (winner, file) = hits.head
    val builtInShadow =
      if ReviewerPrompts.all.exists(_.name == slug) then
        List(ReviewerOrigin.BuiltIn)
      else Nil
    reviewerFrom(slug, file.parsed, file.path.toString).map: reviewer =>
      DiscoveredReviewer(
        reviewer = reviewer,
        tier = winner,
        shadows = hits.tail.map(_._1.origin) ++ builtInShadow
      )

  /** One candidate file, read once: its path, for the message a malformed one
    * raises, and its parsed frontmatter and body.
    */
  private case class ReviewerFile(path: os.Path, parsed: ParsedPrompt)

  /** Names a `.md` file may carry to sit in a reviewer directory without being
    * one. Anything else without frontmatter is an author who forgot the block,
    * not a document — and a reviewer dropped for that reads as a clean review.
    */
  private def isDocument(path: os.Path): Boolean =
    val stem = path.baseName.toLowerCase(Locale.ROOT)
    stem == "readme" || stem.startsWith("_")

  /** Reviewer `.md` files directly in `dir`, keyed by lower-cased filename
    * stem; empty if `dir` doesn't exist.
    *
    * [[isDocument]] names the files that may sit here without being reviewers.
    * Every other `.md` must parse as one: a block the parser can't read, or no
    * block at all, is a broken reviewer and [[reviewerFrom]] reports it.
    *
    * A symlinked `.md` under the PROJECT tier aborts: that directory is
    * committed, and orca runs against arbitrary cloned repos, so reading
    * through a link there would make a file from outside the tree a reviewer's
    * system prompt (ADR 0019's rule). The global tier is the user's own config
    * home, read through links like `settings.properties` beside it — a dotfiles
    * manager that links each file in is normal there.
    */
  private def reviewerFiles(
      dir: os.Path,
      tier: ReviewerFileTier
  ): Map[String, ReviewerFile] =
    if !os.isDir(dir) then Map.empty
    else
      val markdown = os.list(dir).filter(_.last.endsWith(".md"))
      if tier == ReviewerFileTier.Project then
        // `os.isLink` is lstat/no-follow, so this catches a dangling link too.
        markdown
          .find(os.isLink)
          .foreach: link =>
            throw new OrcaFlowException(
              s"$link is a symlink — refusing to read a reviewer prompt " +
                "through it; copy the file into the directory instead of " +
                "linking it"
            )
      val files = markdown
        .filterNot(isDocument)
        .filter(os.isFile)
        .map(p => ReviewerFile(p, PromptResource.parseWithMetadata(os.read(p))))
      val bySlug = files.groupBy(_.path.baseName.toLowerCase(Locale.ROOT))
      // Slugs are compared lower-cased, so on a case-sensitive filesystem two
      // files can claim one; picking a winner would drop the other silently.
      bySlug
        .find(_._2.sizeIs > 1)
        .foreach: (slug, colliding) =>
          throw new OrcaFlowException(
            s"reviewer '$slug' is claimed by more than one file in $dir " +
              s"(${colliding.map(_.path.last).sorted.mkString(", ")}) — " +
              "reviewer names are compared case-insensitively; keep one"
          )
      bySlug.view.mapValues(_.head).toMap
