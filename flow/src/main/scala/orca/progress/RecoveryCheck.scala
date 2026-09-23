package orca.progress

/** Validation of an untrusted [[ProgressHeader]] read back on resume (ADR 0018
  * §2.4/§2.5).
  *
  * The progress log is human-visible and pushable, so its header is untrusted
  * input on load: it may have been hand-edited or carried onto the wrong branch
  * by a merge. Its refs are well-formed by decode (a malformed one fails to
  * parse); before any destructive git action (checkout, reset --hard, delete)
  * the runtime validates the rest here. A failure is a hard signal — the caller
  * aborts the run rather than silently proceeding or starting fresh.
  */
object RecoveryCheck:

  /** Validate the header before any destructive action. Returns `Left(reason)`
    * on the first failure, `Right(featureBranch)` — the header's `branch`
    * minted as a [[FeatureBranch]] — when the header is trustworthy, so the
    * caller (`FlowLifecycle.setup`'s resume arm) can bind `FlowSetup` to a
    * typed branch without a second, redundant resolve call.
    *
    * Checks, in order: `branch` isn't protected, via
    * [[FeatureBranch.resolveReused]] (unions `protectedBranches` — the repo's
    * actual default branch — with the `main`/`master` floor,
    * case-insensitively); `userPrompt` is the current prompt.
    *
    * The caller separately cross-checks `branch` against the actual current
    * branch (R30), so a reused non-slug branch name is accepted only when it is
    * the branch we're already sitting on.
    */
  def validateHeader(
      header: ProgressHeader,
      userPrompt: String,
      protectedBranches: Set[String]
  ): Either[String, FeatureBranch] =
    FeatureBranch.resolveReused(header.branch, protectedBranches) match
      case Left(ProtectedBranchRefused(name)) =>
        Left(s"branch '$name' is a protected branch")
      case Right(featureBranch) =>
        if header.userPrompt != userPrompt then
          Left("userPrompt does not match the current prompt")
        else Right(featureBranch)
