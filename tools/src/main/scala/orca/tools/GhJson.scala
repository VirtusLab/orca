package orca.tools

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  ConfiguredJsonValueCodec,
  JsonCodecMaker
}

// Wire-format DTOs for the `gh`/`git` JSON output that `OsGitHubTool` parses.
// Kept separate from the public GitHubTool contract: these mirror the GitHub
// CLI/API shape and change for a different reason than the tool's own API.
// All `private[orca]` — internal to the tools layer, never part of the public
// surface (callers see `Comment`/`Issue`/`PrHandle`, not these).

private[orca] case class GhCheck(
    // CheckRun entries use `status`/`conclusion`; legacy commit-status entries
    // use only `state`. Both are optional so a single codec handles both.
    status: Option[String] = None,
    conclusion: Option[String] = None,
    state: Option[String] = None,
    name: Option[String] = None
) derives ConfiguredJsonValueCodec

private[orca] case class GhCheckRollup(
    statusCheckRollup: List[GhCheck]
) derives ConfiguredJsonValueCodec

private[orca] case class GhCommentJson(
    body: String,
    user: GhUserJson
) derives ConfiguredJsonValueCodec

/** Comment JSON with its numeric id, used internally by [[OsGitHubTool]] to
  * support the PATCH path in `upsertComment`. The id is a GitHub-issued int.
  * Not exposed publicly — callers see the id-free [[Comment]] type.
  */
private[orca] case class GhIdentifiedCommentJson(
    id: Long,
    body: String,
    user: GhUserJson
) derives ConfiguredJsonValueCodec

/** Minimal PR fields returned by `gh pr list --json number,url`. Used by
  * [[OsGitHubTool.findOpenPr]] to map a head branch to an open PR. Only the URL
  * is needed: the owner/repo/number are extracted from it via [[PrUrlPattern]]
  * so no separate `headRefName` field is required.
  */
private[orca] case class GhPrListJson(
    number: Int,
    url: String
) derives ConfiguredJsonValueCodec

private[orca] case class GhUserJson(login: String)
    derives ConfiguredJsonValueCodec

private[orca] case class GhIssueJson(
    title: String,
    // Issues without a body return `null` from the API; the codec
    // treats a missing key as None and a null literal as None too.
    body: Option[String] = None,
    user: GhUserJson,
    state: String
) derives ConfiguredJsonValueCodec

private[orca] given ghCommentListCodec: JsonValueCodec[List[GhCommentJson]] =
  JsonCodecMaker.make
private[orca] given ghIdentifiedCommentListCodec
    : JsonValueCodec[List[GhIdentifiedCommentJson]] =
  JsonCodecMaker.make
private[orca] given ghPrListCodec: JsonValueCodec[List[GhPrListJson]] =
  JsonCodecMaker.make
