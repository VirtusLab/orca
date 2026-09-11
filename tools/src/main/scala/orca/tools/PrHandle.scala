package orca.tools

import orca.agents.JsonData

/** A handle to an open pull request. `host` is the GitHub host the PR lives on
  * — `github.com` or a GitHub Enterprise hostname — and every gh call taking
  * this handle is routed back to it. `derives JsonData` so a `stage` can record
  * and replay a `PrHandle` result (ADR 0018 §3.2).
  */
case class PrHandle(host: String, owner: String, repo: String, number: Int)
    derives JsonData:
  /** Canonical GitHub short-form `<owner>/<repo>#<number>`. */
  def shortRef: String = s"$owner/$repo#$number"

  /** Browser URL for the PR. */
  def url: String = s"https://$host/$owner/$repo/pull/$number"

object PrHandle:
  // The host is spliced into `gh` arguments and back into `url`, so it is
  // restricted to a hostname charset with an optional port: userinfo (`@`),
  // `?` and `#` must not survive parsing, or `url` would render a link
  // pointing somewhere other than where it reads — the same reason
  // [[IssueHandle]] restricts its owner/repo charsets. Only `https` is
  // accepted, since `url` renders `https` back.
  private val UrlPattern =
    """https://([A-Za-z0-9.-]+(?::\d+)?)/([^/]+)/([^/]+)/pull/(\d+)""".r

  /** The first PR browser URL in `s` as a handle — the inverse of `url`, kept
    * next to it so the two halves of the format stay in step. The host is
    * captured, so a PR URL on a GitHub Enterprise instance parses into a handle
    * that keeps talking to that instance.
    */
  def fromUrl(s: String): Option[PrHandle] =
    UrlPattern
      .findFirstMatchIn(s)
      .map: m =>
        PrHandle(m.group(1), m.group(2), m.group(3), m.group(4).toInt)
