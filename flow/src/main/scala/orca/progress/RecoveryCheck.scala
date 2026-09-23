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

  /** Validate the header before any destructive action. Returns `Left(reason)`
    * on the first failure, `Right(featureBranch)` — the header's `branch`
    * minted as a [[FeatureBranch]] — when the header is trustworthy, so the
    * caller (`FlowLifecycle.setup`'s resume arm) can bind `FlowSetup` to a
    * typed branch without a second, redundant resolve call.
    *
    * Checks, in order: `startingBranch` passes
    * [[FeatureBranch.isSafeReusedRef]] (the weaker shape check — `branch` may
    * be a reused current branch, ADR 0018 amendment, so a strict slug check
    * would reject it); `branch` passes the same check and isn't protected, via
    * [[FeatureBranch.resolveReused]] (unions `protectedBranches` — the repo's
    * actual default branch — with the `main`/`master` floor,
    * case-insensitively); `userPrompt` is the current prompt.
    *
    * The caller separately cross-checks `branch` against the actual current
    * branch (R30) — combined with `FeatureBranch.isSafeReusedRef` here, that's
    * what makes a reused non-slug branch name safe to accept: it must both look
    * like a safe ref AND be the branch we're already sitting on.
    */
  def validateHeader(
      header: ProgressHeader,
      userPrompt: String,
      protectedBranches: Set[String]
  ): Either[String, FeatureBranch] =
    if !FeatureBranch.isSafeReusedRef(header.startingBranch) then
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
