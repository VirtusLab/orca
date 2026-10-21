package orca.runner

import orca.WorkspaceWrite
import orca.tools.{GitHubTool, PrCreateFailed, PrHandle, PrReadiness}

/** The `gh` a run's flow sees: `underlying` with a `createPr` that opens
  * `runReadiness` when its caller passes no readiness.
  */
private[orca] class RunGitHubTool(
    underlying: GitHubTool,
    runReadiness: PrReadiness
) extends GitHubTool:
  export underlying.{createPr as _, *}

  def createPr(
      title: String,
      body: String,
      readiness: Option[PrReadiness]
  )(using WorkspaceWrite): Either[PrCreateFailed, PrHandle] =
    underlying.createPr(title, body, readiness.orElse(Some(runReadiness)))
