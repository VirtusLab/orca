package orca.tools

/** What [[GitHubTool.availability]] found: whether a PR can be opened from this
  * checkout, and against which host.
  */
enum GitHubAvailability:
  /** A PR can be opened against `owner`/`repo` on `host` — the repository gh
    * resolves from this checkout, which is the one `gh pr create` would target.
    */
  case Available(host: String, owner: String, repo: String)

  /** No PR can be opened; `why` says what was found instead. */
  case Unavailable(why: GitHubUnavailable)

/** Why [[GitHubTool.availability]] found no repository to open a PR against. */
enum GitHubUnavailable:
  /** The checkout has no `origin` remote — there is nothing to open a PR
    * against.
    */
  case NoRemote

  /** `origin` names a host gh holds no credential for. gh only ever logs in to
    * GitHub, so this is either a GitHub Enterprise host missing its `gh auth
    * login --hostname <host>`, or a host that is not GitHub at all.
    */
  case NotGitHub(host: String)

  /** `origin` has no host to ask gh about at all — a local path or a bare
    * repository (`/srv/repos/widgets.git`). `remote` is the whole remote URL.
    */
  case NoHost(remote: String)

  /** `host` is GitHub — github.com, or a host gh holds a credential for — but
    * gh gave no usable answer for it: not installed, no credential for
    * github.com, or a failed `gh repo view` (a passing network failure is
    * retried first; no permission or no such repository is not). `reason` is
    * gh's own explanation where it gave one.
    */
  case Unreachable(host: String, reason: String)

  /** git could not be run to name the origin, so nothing is known about the
    * checkout — not even whether it has a remote. `reason` is what running git
    * threw.
    */
  case GitUnusable(reason: String)

  /** The diagnosis, with gh's remedy where one exists, for a consumer to append
    * its own next action to.
    */
  def explanation: String = this match
    case NoRemote => "this checkout has no git remote"
    case NotGitHub(host) =>
      s"origin is on $host, which gh holds no credential for — if it is a " +
        s"GitHub Enterprise host, `gh auth login --hostname $host` lets orca " +
        "use it"
    case NoHost(remote) =>
      s"origin is a local remote ($remote), not a GitHub repository"
    case Unreachable(host, reason) => s"cannot reach GitHub ($host): $reason"
    case GitUnusable(reason) =>
      s"could not run git to find the remote: $reason"
