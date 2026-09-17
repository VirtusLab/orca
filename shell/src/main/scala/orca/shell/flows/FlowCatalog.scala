package orca.shell.flows

import orca.discovery.{Origin, TierPrecedence}

/** One flow-listing row: the winning tier's script plus the tiers it shadowed,
  * so the menu can annotate `[shadows global, built-in]`.
  */
private[shell] case class DiscoveredFlow(
    name: String,
    description: Option[String],
    origin: Origin,
    path: os.Path,
    shadows: List[Origin]
)

/** Discovers `.sc` flow scripts across the three tiers and resolves per-name
  * precedence (ADR 0021 §5).
  */
private[shell] object FlowCatalog:

  /** One entry per filename across `projectFlows`, `globalFlows`, and
    * `builtIns`, sorted by name. Precedence project > global > built-in: the
    * winner's tier, path, and description are carried on the entry, and every
    * lower-precedence tier that also has the name is recorded in `shadows`
    * (highest to lowest). Only `*.sc` files count; a missing tier directory
    * contributes no entries.
    */
  def list(
      projectFlows: os.Path,
      globalFlows: os.Path,
      builtIns: os.Path
  ): List[DiscoveredFlow] =
    TierPrecedence
      .resolve(
        List(
          Origin.Project -> scriptsByName(projectFlows),
          Origin.Global -> scriptsByName(globalFlows),
          Origin.BuiltIn -> scriptsByName(builtIns)
        )
      )
      .map: winner =>
        DiscoveredFlow(
          name = winner.key,
          description = FlowDescription.ofFile(winner.value),
          origin = winner.tier,
          path = winner.value,
          shadows = winner.shadows
        )

  /** `*.sc` files directly in `dir`, keyed by filename; empty if `dir` doesn't
    * exist. Symlinked entries are excluded (`os.isLink`, lstat/no-follow): a
    * committed symlink `x.sc` (or a symlinked tier dir, guarded upstream at the
    * project tier by `OrcaDir.assertNoOrcaSymlinks`) would otherwise let View
    * disclose, and Edit write through to, a target outside the tree.
    */
  private def scriptsByName(dir: os.Path): Map[String, os.Path] =
    if !os.isDir(dir) then Map.empty
    else
      os.list(dir)
        .filter(p => !os.isLink(p) && os.isFile(p) && p.last.endsWith(".sc"))
        .map(p => p.last -> p)
        .toMap
