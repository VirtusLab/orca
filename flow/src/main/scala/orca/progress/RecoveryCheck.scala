package orca.progress

/** Validation of an untrusted [[ProgressHeader]] read back on resume (ADR 0018
  * §2.4/§2.5).
  *
  * The progress log is human-visible and pushable, so its header is untrusted
  * input on load: it may have been hand-edited or carried onto the wrong branch
  * by a merge. Before any destructive git action (checkout, reset --hard,
  * delete) the runtime validates it here. A failure is a hard signal — the
  * caller aborts the run rather than silently proceeding or starting fresh.
  */
object RecoveryCheck:

  /** Whether `s` is a safe git ref for orca's purposes: non-empty, and every
    * `/`-separated segment matches the slug shape `^[a-z0-9][a-z0-9-]*$` (the
    * same charset [[orca.BranchNamingStrategy.slug]] produces). Issue branches
    * like `fix/issue-42` pass; ``, `-x`, `a/..`, `a b`, `Feat` are rejected.
    *
    * The leading-alphanumeric requirement blocks a name beginning with `-`
    * (which `git`/`gh` would read as a CLI flag) and bans
    * path-traversal/whitespace segments. Referencing
    * `BranchNamingStrategy.isSlugSegment` keeps producer and validator from
    * drifting.
    */
  def isSafeBranchRef(s: String): Boolean =
    s.nonEmpty && s
      .split("/", -1)
      .forall(orca.BranchNamingStrategy.isSlugSegment)

  /** Whether `s` is safe enough to reuse as-is for a branch orca did NOT mint
    * itself — a user's pre-existing current branch, in skip-branch mode (ADR
    * 0018 amendment). Weaker than [[isSafeBranchRef]] (no slug shape, so mixed
    * case and `feature/JIRA-123`-style names pass), but still refuses anything
    * that could act as CLI-flag/argument injection into `git`/`gh`, a
    * path-traversal ref, or a shell-glob DoS against `git branch --list`: a
    * leading `-`, whitespace/control characters, a glob metacharacter (`*`,
    * `?`, `[`, `\`), `..` anywhere (path traversal, forbidden in any git ref),
    * a `.lock` suffix (git's own lockfile convention), an empty `/`-separated
    * segment (leading/trailing/doubled `/`), or the literal pseudo-ref `HEAD`
    * (never a real branch; a detached-HEAD `currentBranch()` reads back as this
    * literal string, and a header must not be able to claim it either).
    */
  def isSafeGitRef(s: String): Boolean =
    s.nonEmpty &&
      s != "HEAD" &&
      !s.startsWith("-") &&
      !s.exists(c => c.isWhitespace || c.isControl || "*?[\\".contains(c)) &&
      !s.endsWith(".lock") &&
      !s.contains("..") &&
      !s.split("/", -1).exists(_.isEmpty)

  /** Branches that are always protected regardless of the repo's configured
    * default — the floor [[validateHeader]] enforces (ADR 0018). The runtime
    * adds the repo's actual default branch on top of these.
    */
  val alwaysProtected: Set[String] = Set("main", "master")

  /** Validate the header before any destructive action. Returns `Left(reason)`
    * on the first failure, `Right(featureBranch)` — the header's `branch`
    * minted as a [[FeatureBranch]] — when the header is trustworthy, so the
    * caller (`FlowLifecycle.setup`'s resume arm) can bind `FlowSetup` to a
    * typed branch without a second, redundant resolve call.
    *
    *   - `branch` and `startingBranch` must pass [[isSafeGitRef]] — the weaker
    *     check, not [[isSafeBranchRef]]'s strict slug shape: `branch` may be a
    *     reused current branch from a skip-branch run (ADR 0018 amendment), and
    *     `startingBranch` is always a pre-existing, orca-never-minted branch. A
    *     minted slug name always passes the weaker check too, so this doesn't
    *     loosen validation for the common case.
    *   - `branch` must not be a protected branch (`startingBranch` may be) —
    *     delegated to [[FeatureBranch.resolveReused]], which unions
    *     `protectedBranches` with the `main`/`master` floor.
    *   - `promptHash` must equal the recomputed hash of the current prompt.
    *
    * `protectedBranches` lets the runtime pass the repo's ACTUAL default branch
    * (e.g. `trunk`/`develop`), so a tampered header naming it as a feature
    * branch is refused — not just `main`/`master`. Compared case-insensitively.
    *
    * The caller separately cross-checks `branch` against the actual current
    * branch (R30) — that check, combined with `isSafeGitRef` here, is what
    * makes a reused non-slug branch name safe to accept: it must both look like
    * a safe ref AND be the branch we're already sitting on.
    */
  def validateHeader(
      header: ProgressHeader,
      userPrompt: String,
      protectedBranches: Set[String]
  ): Either[String, FeatureBranch] =
    if !isSafeGitRef(header.startingBranch) then
      Left(s"startingBranch '${header.startingBranch}' is not a safe ref")
    else
      FeatureBranch.resolveReused(header.branch, protectedBranches) match
        case Left(ProtectedBranchRefused(name)) =>
          Left(s"branch '$name' is a protected branch")
        case Left(UnsafeBranchRefRefused(name)) =>
          Left(s"branch '$name' is not a safe ref")
        case Right(featureBranch) =>
          if header.promptHash != ProgressStore.hashPrompt(userPrompt) then
            Left("promptHash does not match the current prompt")
          else Right(featureBranch)
