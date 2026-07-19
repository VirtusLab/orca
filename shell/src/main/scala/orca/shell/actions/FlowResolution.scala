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

  private val descriptionLinesToRead = 50

  /** [[FlowCatalog.list]] across all three tiers, guarding the project tier's
    * component chain against a committed symlink first
    * ([[OrcaDir.assertNoOrcaSymlinks]]) — the exact guarded build the menu's
    * flow picker used inline before this moved out. Left on either the guard or
    * the built-in extraction failing (full-disk, permission error, …).
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
              .toRight(s"no flow named '$ref' found in the catalog")

  private def fromPath(path: os.Path): DiscoveredFlow =
    DiscoveredFlow(
      name = path.last,
      description = FlowDescription.extract(
        os.read.lines.stream(path).take(descriptionLinesToRead).toList
      ),
      origin = FlowOrigin.Project,
      path = path,
      shadows = Nil
    )
