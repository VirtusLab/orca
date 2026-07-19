package orca.shell.actions

import orca.OrcaDir
import orca.settings.GlobalSettings
import orca.shell.ShellVersion
import orca.shell.flows.{
  BuiltInFlows,
  DiscoveredFlow,
  FlowCatalog,
  FlowDescription,
  FlowOrigin
}

import scala.util.Try
import scala.util.control.NonFatal

/** Resolves flows against the three-tier catalog (ADR 0021 §5) for callers that
  * aren't driving an interactive picker — the menu's own guarded catalog build
  * plus name/path lookup, shared by every action that needs a flow without a
  * `ui.select` (a non-interactive CLI caller in particular).
  */
private[shell] object FlowResolution:

  /** [[FlowCatalog.list]] across all three tiers, guarding the project tier's
    * component chain against a committed symlink first
    * ([[OrcaDir.assertNoOrcaSymlinks]]) — the same guarded build the menu's
    * flow picker uses. Left on either the guard or the built-in extraction
    * failing (full-disk, permission error, …).
    */
  def list(workDir: os.Path): Either[String, List[DiscoveredFlow]] =
    try
      OrcaDir.assertNoOrcaSymlinks(workDir, OrcaDir.flowsPath(workDir))
      Right(
        FlowCatalog.list(
          OrcaDir.flowsPath(workDir),
          GlobalSettings.defaultFlows,
          BuiltInFlows.extracted(sys.env.get, os.home, ShellVersion.value)
        )
      )
    catch case NonFatal(e) => Left(s"couldn't list flows — ${e.getMessage}")

  /** Resolves `ref` for a non-interactive caller: a token containing `/`, or
    * naming an existing `.sc` file relative to `workDir`, is a path read
    * directly off disk; otherwise it's a catalog name (`.sc` suffix optional),
    * looked up in [[list]] with the same project > global > built-in precedence
    * the interactive picker shows. A path-resolved flow reports
    * [[FlowOrigin.Project]] with no shadowed tiers — it isn't a catalog entry,
    * but Project matches how it behaves (edited in place, never offered a
    * customize-into-a-tier step).
    */
  def resolve(ref: String, workDir: os.Path): Either[String, DiscoveredFlow] =
    val asPath = Try(os.Path(ref, workDir)).toOption
    val pathHit = asPath.filter(p =>
      (ref.contains("/") || ref.endsWith(".sc")) && os.isFile(p)
    )
    pathHit match
      case Some(path) => Right(fromPath(path))
      case None =>
        if ref.contains("/") then Left(s"no such flow file: $ref")
        else
          val name = if ref.endsWith(".sc") then ref else s"$ref.sc"
          list(workDir).flatMap: flows =>
            flows
              .find(_.name == name)
              .toRight(notFoundMessage(ref, flows))

  /** `no flow named '<ref>' found in the catalog`, or, when any catalog name
    * looks close enough to be a typo of `ref` ([[nearMatches]]), `no flow named
    * '<ref>'; did you mean: a.sc, b.sc?` instead.
    */
  private def notFoundMessage(
      ref: String,
      flows: List[DiscoveredFlow]
  ): String =
    nearMatches(ref, flows) match
      case Nil => s"no flow named '$ref' found in the catalog"
      case matches =>
        s"no flow named '$ref'; did you mean: ${matches.mkString(", ")}?"

  /** Catalog names that look like a typo of `ref`: a substring match either way
    * (`ref` inside the name, or the name inside `ref`), or a small edit
    * distance ([[levenshtein]] <= 2) — cheap enough over a flow catalog's size
    * (tens of entries, not thousands). Compared case-insensitively and
    * `.sc`-stripped, so `implment` still matches `implement.sc`. Sorted for a
    * deterministic message.
    */
  private def nearMatches(
      ref: String,
      flows: List[DiscoveredFlow]
  ): List[String] =
    val target = ref.toLowerCase(java.util.Locale.ROOT).stripSuffix(".sc")
    flows
      .map(_.name)
      .filter: candidate =>
        val base =
          candidate.toLowerCase(java.util.Locale.ROOT).stripSuffix(".sc")
        base.contains(target) || target.contains(
          base
        ) || levenshtein(base, target) <= 2
      .distinct
      .sorted

  /** Classic Levenshtein edit distance (insert/delete/substitute), via a
    * single-row rolling DP — [[nearMatches]]'s "small typo" check.
    */
  private def levenshtein(a: String, b: String): Int =
    val row = Array.range(0, b.length + 1)
    for i <- 1 to a.length do
      var previousDiagonal = row(0)
      row(0) = i
      for j <- 1 to b.length do
        val previousAbove = row(j)
        row(j) =
          if a(i - 1) == b(j - 1) then previousDiagonal
          else 1 + math.min(previousDiagonal, math.min(row(j - 1), row(j)))
        previousDiagonal = previousAbove
    row(b.length)

  private def fromPath(path: os.Path): DiscoveredFlow =
    DiscoveredFlow(
      name = path.last,
      description = FlowDescription.ofFile(path),
      origin = FlowOrigin.Project,
      path = path,
      shadows = Nil
    )
