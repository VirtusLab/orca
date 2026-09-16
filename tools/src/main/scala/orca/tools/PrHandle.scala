package orca.tools

import orca.agents.JsonData

/** A handle to an open pull request. `host` is the bare GitHub hostname the PR
  * lives on — `github.com` or a GitHub Enterprise hostname, never with a port —
  * and every gh call taking this handle is routed back to it. gh's `--hostname`
  * takes no port, so a GitHub Enterprise host on a non-default port is not
  * supported. `derives JsonData` so a `stage` can record and replay a
  * `PrHandle` result (ADR 0018 §3.2). Only [[PrHandle.fromUrl]] validates the
  * fields; the constructor and the codec take them as given.
  */
case class PrHandle(host: String, owner: String, repo: String, number: Int)
    derives JsonData:
  /** Canonical GitHub short-form `<owner>/<repo>#<number>`. */
  def shortRef: String = s"$owner/$repo#$number"

  /** Browser URL for the PR. */
  def url: String = s"https://$host/$owner/$repo/pull/$number"

object PrHandle:
  // The host is spliced into `gh` arguments and back into `url`, so it is
  // restricted to a hostname charset: userinfo (`@`), `?` and `#` must not
  // survive parsing, or `url` would render a link pointing somewhere other
  // than where it reads — the same reason [[IssueHandle]] restricts its
  // owner/repo charsets, reused here. A port is refused rather than dropped:
  // `url` could not render it back, so the handle would link elsewhere. Only
  // `https` is accepted, since `url` renders `https` back.
  private val UrlPattern =
    s"""https://([A-Za-z0-9.-]+)/(${IssueHandle.Owner})/(${IssueHandle.Repo})/pull/(\\d+)""".r

  /** The first PR browser URL in `s` as a handle — the inverse of `url`, kept
    * next to it so the two halves of the format stay in step. The host is
    * captured, so a PR URL on a GitHub Enterprise instance parses into a handle
    * that keeps talking to that instance; a URL with a port gives no handle.
    */

  def fromUrl(s: String): Option[PrHandle] =
    UrlPattern
      .findFirstMatchIn(s)
      .flatMap: m =>
        // A number too large for an `Int` is no PR number: no handle, no throw.
        m.group(4)
          .toIntOption
          .map(number =>
            PrHandle(
              host = m.group(1),
              owner = m.group(2),
              repo = m.group(3),
              number = number
            )
          )
