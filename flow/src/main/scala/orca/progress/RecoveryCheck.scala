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
    * drifting. For a branch orca did NOT mint itself, see the weaker
    * [[isSafeReusedRef]] instead.
    */
  def isSafeBranchRef(s: String): Boolean =
    s.nonEmpty && s
      .split("/", -1)
      .forall(orca.BranchNamingStrategy.isSlugSegment)

  /** Whether `s` is safe enough to reuse as-is for a branch orca did NOT mint
    * itself — a user's pre-existing current branch, in skip-branch mode (ADR
    * 0018 amendment), or a user-requested branch. Weaker than
    * [[isSafeBranchRef]] (no slug shape, so mixed case and
    * `feature/JIRA-123`-style names pass): `s` only has to satisfy `git
    * check-ref-format --branch`, with Unicode whitespace and control characters
    * refused too. That rules out CLI-flag injection into `git`/`gh` (leading
    * `-`), path traversal (`..`), shell-glob metacharacters, and the literal
    * pseudo-ref `HEAD` (a detached-HEAD `currentBranch()` reads back as it).
    */
  def isSafeReusedRef(s: String): Boolean =
    BranchName.refFormatViolation(s).isEmpty

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
    * Checks, in order: `startingBranch` passes [[isSafeReusedRef]] (the weaker
    * shape check — `branch` may be a reused current branch, ADR 0018 amendment,
    * so a strict slug check would reject it); `branch` passes the same check
    * and isn't protected, via [[FeatureBranch.resolveReused]] (unions
    * `protectedBranches` — the repo's actual default branch — with the
    * `main`/`master` floor, case-insensitively); `userPrompt` is the current
    * prompt.
    *
    * The caller separately cross-checks `branch` against the actual current
    * branch (R30) — combined with `isSafeReusedRef` here, that's what makes a
    * reused non-slug branch name safe to accept: it must both look like a safe
    * ref AND be the branch we're already sitting on.
    */
  def validateHeader(
      header: ProgressHeader,
      userPrompt: String,
      protectedBranches: Set[String]
  ): Either[String, FeatureBranch] =
    if !isSafeReusedRef(header.startingBranch) then
      Left(s"startingBranch '${header.startingBranch}' is not a safe ref")
    else
      FeatureBranch.resolveReused(header.branch, protectedBranches) match
        case Left(ProtectedBranchRefused(name)) =>
          Left(s"branch '$name' is a protected branch")
        case Left(UnsafeBranchRefRefused(name)) =>
          Left(s"branch '$name' is not a safe ref")
        case Right(featureBranch) =>
          if header.userPrompt != userPrompt then
            Left("userPrompt does not match the current prompt")
          else Right(featureBranch)
