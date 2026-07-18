package orca.shell.flows

/** Which of the three tiers (ADR 0021 §5) a flow was read from. */
enum FlowOrigin:
  case Project, Global, BuiltIn

/** One flow-listing row: the winning tier's script plus the tiers it shadowed,
  * so the menu can annotate `[shadows global, built-in]`.
  */
case class DiscoveredFlow(
    name: String,
    description: Option[String],
    origin: FlowOrigin,
    path: os.Path,
    shadows: List[FlowOrigin]
)

/** Discovers `.sc` flow scripts across the three tiers and resolves per-name
  * precedence (ADR 0021 §5).
  */
object FlowCatalog:
  private val descriptionLinesToRead = 50

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
    val byTier = List(
      FlowOrigin.Project -> scriptsByName(projectFlows),
      FlowOrigin.Global -> scriptsByName(globalFlows),
      FlowOrigin.BuiltIn -> scriptsByName(builtIns)
    )
    val names = byTier.flatMap(_._2.keySet).distinct.sorted
    names.map(name => resolve(name, byTier))

  /** The tiers that contain `name`, in precedence order (winner first). */
  private def resolve(
      name: String,
      byTier: List[(FlowOrigin, Map[String, os.Path])]
  ): DiscoveredFlow =
    val hits = byTier.collect:
      case (origin, files) if files.contains(name) => origin -> files(name)
    val (winnerOrigin, winnerPath) = hits.head
    DiscoveredFlow(
      name = name,
      description = FlowDescription.extract(
        os.read.lines.stream(winnerPath).take(descriptionLinesToRead).toList
      ),
      origin = winnerOrigin,
      path = winnerPath,
      shadows = hits.tail.map(_._1)
    )

  /** `*.sc` files directly in `dir`, keyed by filename; empty if `dir` doesn't
    * exist.
    */
  private def scriptsByName(dir: os.Path): Map[String, os.Path] =
    if !os.isDir(dir) then Map.empty
    else
      os.list(dir)
        .filter(p => os.isFile(p) && p.last.endsWith(".sc"))
        .map(p => p.last -> p)
        .toMap
