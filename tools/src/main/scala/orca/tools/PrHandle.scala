package orca.tools

import orca.agents.JsonData

/** A handle to an open pull request. `host` is the bare GitHub hostname the PR
  * lives on — `github.com` or a GitHub Enterprise hostname — and every gh call
  * taking this handle is routed back to it. Never with a port: gh's
  * `--hostname` takes none, so a GitHub Enterprise host on a non-default port
  * is unsupported.
  *
  * Built only through [[PrHandle.from]] or [[PrHandle.fromUrl]], and the JSON
  * codec decodes through them, so a handle read back from a stage record is
  * validated like one parsed from gh.
  */
case class PrHandle private (
    host: String,
    owner: String,
    repo: String,
    number: Int
):
  /** Canonical GitHub short-form `<owner>/<repo>#<number>`. */
  def shortRef: String = s"$owner/$repo#$number"

  /** Browser URL for the PR. */
  def url: String = s"https://$host/$owner/$repo/pull/$number"

object PrHandle:
  // Owner and repo are spliced into `gh` arguments and back into `url` next
  // to the host, so they are restricted to GitHub's name charsets for the
  // same reason [[HostName]] restricts the host. Only `https` is accepted:
  // `url` renders nothing else back.
  private val HostPattern = HostName.r
  private val OwnerPattern = IssueHandle.Owner.r
  private val RepoPattern = IssueHandle.Repo.r

  private val UrlPattern =
    s"""https://($HostName)/(${IssueHandle.Owner})/(${IssueHandle.Repo})/pull/(\\d+)""".r

  /** A handle for PR `number` of `owner/repo` on `host`; a `Left` names the
    * first invalid field.
    */
  def from(
      host: String,
      owner: String,
      repo: String,
      number: Int
  ): Either[String, PrHandle] =
    if !HostPattern.matches(host) then Left(s"'$host' is not a hostname")
    else if !OwnerPattern.matches(owner) then
      Left(s"'$owner' is not a GitHub owner")
    else if !RepoPattern.matches(repo) then
      Left(s"'$repo' is not a GitHub repository")
    else if number < 1 then Left(s"$number is not a PR number")
    else Right(PrHandle(host, owner, repo, number))

  /** The first PR browser URL in `s` as a handle — the inverse of `url`, kept
    * next to it so the two halves of the format stay in step. The host is
    * captured, so a GitHub Enterprise URL parses into a handle that keeps
    * talking to that instance; a URL with a port gives none.
    */
  def fromUrl(s: String): Option[PrHandle] =
    UrlPattern
      .findFirstMatchIn(s)
      .flatMap:
        case UrlPattern(host, owner, repo, number) =>
          fromFields(host, owner, repo, number).toOption

  /** `s` as a handle when all of it is a PR browser URL. */
  private def fromExactUrl(s: String): Either[String, PrHandle] =
    s match
      case UrlPattern(host, owner, repo, number) =>
        fromFields(host, owner, repo, number)
      case _ => Left(s"'$s' is not a PR URL")

  private def fromFields(
      host: String,
      owner: String,
      repo: String,
      number: String
  ): Either[String, PrHandle] =
    // A number too large for an `Int` is no PR number: no handle, no throw.
    number.toIntOption
      .toRight(s"$number is not a PR number")
      .flatMap(from(host, owner, repo, _))

  /** Travels as its `url`. */
  given JsonData[PrHandle] = JsonData.fromString(fromExactUrl, _.url)
