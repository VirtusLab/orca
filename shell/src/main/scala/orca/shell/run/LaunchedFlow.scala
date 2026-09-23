package orca.shell.run

import orca.progress.FlowSource
import orca.shell.flows.DiscoveredFlow

/** A flow script to launch, and how the user named it, which the run records.
  */
private[shell] case class LaunchedFlow(path: os.Path, source: FlowSource):
  def fileName: String = path.last

private[shell] object LaunchedFlow:
  def of(flow: DiscoveredFlow): LaunchedFlow =
    LaunchedFlow(flow.path, flow.source)

  /** A script launched by its path rather than through the catalog. */
  def file(path: os.Path): LaunchedFlow =
    LaunchedFlow(path, FlowSource.File(path.toString))
