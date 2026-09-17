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
          Origin.Project -> scriptsByName(projectFlows, Origin.Project),
          Origin.Global -> scriptsByName(globalFlows, Origin.Global),
          Origin.BuiltIn -> scriptsByName(builtIns, Origin.BuiltIn)
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
    * exist.
    *
    * A symlinked entry is excluded in the PROJECT tier only (`os.isLink`,
    * lstat/no-follow): that directory is committed and orca runs against
    * arbitrary cloned repos, so a committed symlink `x.sc` (or a symlinked tier
    * dir, guarded upstream by `OrcaDir.assertNoOrcaSymlinks`) would otherwise
    * let View disclose, and Edit write through to, a target outside the tree.
    * The global tier is the user's own config home and the built-in tier is
    * orca's own extraction cache, so both are read through links the way
    * reviewers read theirs (ADR 0023) — a dotfiles manager that links each file
    * in is normal there. A link with no target fails `os.isFile` and is dropped
    * like any other unusable entry.
    */
  private def scriptsByName(
      dir: os.Path,
      origin: Origin
  ): Map[String, os.Path] =
    if !os.isDir(dir) then Map.empty
    else
      val refusesLinks = origin == Origin.Project
      os.list(dir)
        .filter(p => !(refusesLinks && os.isLink(p)))
        .filter(p => os.isFile(p) && p.last.endsWith(".sc"))
        .map(p => p.last -> p)
        .toMap
