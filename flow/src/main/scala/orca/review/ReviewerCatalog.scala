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

/** The reviewer definitions a run works from. `discovered` is the whole of it:
  * [[all]] and [[minimal]] are the shipped lists with each discovered slug
  * substituted in where it already sits, and the rest appended — so a catalog
  * cannot describe a reviewer its roster doesn't run.
  */
private[orca] case class ReviewerCatalog(
    discovered: List[DiscoveredReviewer]
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
  def describe: Option[String] =
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

private[orca] object ReviewerCatalog:

  /** The shipped set alone — no tier directory contributed anything. */
  val builtIn: ReviewerCatalog = ReviewerCatalog(Nil)

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
  def discover(projectDir: os.Path, globalDir: os.Path): ReviewerCatalog =
    val byTier = List(
      ReviewerFileTier.Project -> reviewerFiles(projectDir),
      ReviewerFileTier.Global -> reviewerFiles(globalDir)
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
    ReviewerCatalog(discovered)

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

  /** Reviewer `.md` files directly in `dir`, keyed by lower-cased filename
    * stem; empty if `dir` doesn't exist.
    *
    * A `.md` file with no frontmatter block at all is a document, not a broken
    * reviewer, and is skipped: the directory is committed, so it holds the
    * project's own notes and `README.md` alongside the prompts.
    *
    * A symlinked `.md` aborts instead. Reading one would make a file from
    * outside the tree the system prompt of a reviewer that runs on every
    * review; skipping it silently would drop a reviewer the user installed and
    * let the review come back clean without it.
    */
  private def reviewerFiles(dir: os.Path): Map[String, ReviewerFile] =
    if !os.isDir(dir) then Map.empty
    else
      val markdown = os.list(dir).filter(_.last.endsWith(".md"))
      // `os.isLink` is lstat/no-follow, so this catches a dangling link too.
      markdown
        .find(os.isLink)
        .foreach: link =>
          throw new OrcaFlowException(
            s"$link is a symlink — refusing to read a reviewer prompt through " +
              "it; copy the file into the directory instead of linking it"
          )
      markdown
        .filter(os.isFile)
        .map(p => ReviewerFile(p, PromptResource.parseWithMetadata(os.read(p))))
        .filter(_.parsed.metadata.nonEmpty)
        .map(f => f.path.baseName.toLowerCase(Locale.ROOT) -> f)
        .toMap
