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
    *   - `branch` and `startingBranch` must be safe refs.
    *   - `branch` must not be a protected branch (`startingBranch` may be) —
    *     delegated to [[FeatureBranch.resolve]], which unions
    *     `protectedBranches` with the `main`/`master` floor.
    *   - `promptHash` must equal the recomputed hash of the current prompt.
    *
    * `protectedBranches` lets the runtime pass the repo's ACTUAL default branch
    * (e.g. `trunk`/`develop`), so a tampered header naming it as a feature
    * branch is refused — not just `main`/`master`. Compared case-insensitively.
    */
  def validateHeader(
      header: ProgressHeader,
      userPrompt: String,
      protectedBranches: Set[String]
  ): Either[String, FeatureBranch] =
    if !isSafeBranchRef(header.branch) then
      Left(s"branch '${header.branch}' is not a safe ref")
    else if !isSafeBranchRef(header.startingBranch) then
      Left(s"startingBranch '${header.startingBranch}' is not a safe ref")
    else
      FeatureBranch.resolve(header.branch, protectedBranches) match
        case Left(ProtectedBranchRefused(name)) =>
          Left(s"branch '$name' is a protected branch")
        case Left(UnsafeBranchRefRefused(name)) =>
          // Unreachable: `header.branch` already passed `isSafeBranchRef` above.
          // This arm exists only for exhaustiveness.
          Left(s"branch '$name' is not a safe ref")
        case Right(featureBranch) =>
          if header.promptHash != ProgressStore.hashPrompt(userPrompt) then
            Left("promptHash does not match the current prompt")
          else Right(featureBranch)
