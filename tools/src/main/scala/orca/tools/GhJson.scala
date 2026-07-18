package orca.tools

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  ConfiguredJsonValueCodec,
  JsonCodecMaker
}

// Wire-format DTOs for the `gh`/`git` JSON that `OsGitHubTool` parses. Kept
// separate from the public GitHubTool contract: they mirror the GitHub CLI/API
// shape and change for a different reason than the tool's own API. Callers see
// `Comment`/`Issue`/`PrHandle`, not these.

private[tools] case class GhCheck(
    // CheckRun entries use `status`/`conclusion`; commit-status entries use
    // only `state`. All optional so a single codec handles both.
    status: Option[String] = None,
    conclusion: Option[String] = None,
    state: Option[String] = None,
    name: Option[String] = None
) derives ConfiguredJsonValueCodec

private[tools] case class GhCheckRollup(
    statusCheckRollup: List[GhCheck]
) derives ConfiguredJsonValueCodec

private[tools] case class GhCommentJson(
    body: String,
    user: GhUserJson
) derives ConfiguredJsonValueCodec

/** Comment JSON with its GitHub-issued numeric id, used by [[OsGitHubTool]] for
  * the PATCH path in `upsertComment`. Callers see the id-free [[Comment]] type.
  */
private[tools] case class GhIdentifiedCommentJson(
    id: Long,
    body: String,
    user: GhUserJson
) derives ConfiguredJsonValueCodec

/** Minimal PR fields from `gh pr list --json number,url`, used by
  * [[OsGitHubTool.findOpenPr]] to map a head branch to an open PR. The
  * owner/repo/number are extracted from the URL via [[PrUrlPattern]].
  */
private[tools] case class GhPrListJson(
    number: Int,
    url: String
) derives ConfiguredJsonValueCodec

private[tools] case class GhUserJson(login: String)
    derives ConfiguredJsonValueCodec

private[tools] case class GhIssueJson(
    title: String,
    // Issues without a body return `null`; the codec maps both a missing key
    // and a null literal to None.
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
