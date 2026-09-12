package orca.tools

/** What [[GitHubTool.availability]] found: whether a PR can be opened from this
  * checkout, and against which host.
  */
enum GitHubAvailability:
  /** The checkout has no `origin` remote — there is nothing to open a PR
    * against.
    */
  case NoRemote

  /** `origin` names a host gh cannot be asked about, since gh only ever logs in
    * to GitHub — a GitHub Enterprise host the user has not run `gh auth login
    * --hostname <host>` for lands here, as does a host that is simply not
    * GitHub.
    */
  case NotGitHub(host: String)

  /** `origin` has no host to ask gh about at all — a local path or a bare
    * repository (`/srv/repos/widgets.git`). `remote` is the whole remote URL.
    */
  case NoHost(remote: String)

  /** `host` is GitHub — github.com, or a host gh is logged in to — but gh gave
    * no usable answer for it: not installed, not logged in to github.com, or a
    * `gh repo view` that failed. `reason` is gh's own explanation where it gave
    * one.
    */
  case Unreachable(host: String, reason: String)

  /** A PR can be opened against `owner`/`repo` on `host` — the repository gh
    * resolves from this checkout, which is the one `gh pr create` would target.
    */
  case Available(host: String, owner: String, repo: String)
