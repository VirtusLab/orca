package orca.progress

/** A branch name orca itself may create, commit to, or delete during its own
  * lifecycle bookkeeping (ADR 0018 §2.4/§2.5) — as opposed to any bare `String`
  * git happens to accept. Every [[FeatureBranch]] is guaranteed non-protected
  * (not in the always-protected floor [[RecoveryCheck.alwaysProtected]] unioned
  * with the caller-supplied set, in practice the repo's detected default) AND a
  * safe git ref — [[RecoveryCheck.isSafeBranchRef]]'s strict slug shape via
  * [[resolve]] for an orca-minted name, or [[RecoveryCheck.isSafeReusedRef]]'s
  * weaker shape via [[resolveReused]] for the user's own current branch
  * (skip-branch mode) — so the guarantee holds regardless of which one minted
  * `name`.
  *
  * Every place orca binds itself to a feature branch mints one through
  * [[resolve]] or [[resolveReused]]: `FlowLifecycle`'s fresh-run arm and its
  * resume arm ([[RecoveryCheck.validateHeader]], checking an untrusted
  * persisted header). One pair of constructors, one home for the
  * protected-branch check.
  *
  * Takes `protectedBranches: Set[String]` rather than a `GitTool` so it stays
  * pure and unit-testable without a repo fixture, and the git layer stays
  * `String`-typed and flow-oblivious. Callers unwrap via `.value` only at the
  * git call site.
  */
opaque type FeatureBranch = String

object FeatureBranch:

  /** Attempt to mint a [[FeatureBranch]] from `name`. Refuses `name`
    * (case-insensitively, folded with `Locale.ROOT`) when it is in
    * `protectedBranches` or the always-protected floor
    * ([[RecoveryCheck.alwaysProtected]]); the union mirrors
    * [[RecoveryCheck.validateHeader]]'s check so fresh and resumed runs agree.
    *
    * ALSO refuses `name` when it is not a safe ref shape
    * ([[RecoveryCheck.isSafeBranchRef]]) — this makes the guarantee
    * unconditional rather than resting on callers having slugged `name`. The
    * one call site (a fresh run's orca-minted name) already passes a shape-safe
    * name, so it is a defensive no-op there.
    */
  def resolve(
      name: String,
      protectedBranches: Set[String]
  ): Either[FeatureBranchRefused, FeatureBranch] =
    resolveWith(name, protectedBranches, RecoveryCheck.isSafeBranchRef)

  /** Mint a [[FeatureBranch]] for a branch orca did NOT create — the user's
    * current branch, reused in skip-branch mode (ADR 0018 amendment) instead of
    * minting a fresh one. `name` passes the weaker
    * [[RecoveryCheck.isSafeReusedRef]] shape check rather than the strict slug
    * one: it was never orca-authored, so it may be mixed-case or carry `/`
    * segments like `feature/JIRA-123`. Still refuses a protected branch.
    */
  def resolveReused(
      name: String,
      protectedBranches: Set[String]
  ): Either[FeatureBranchRefused, FeatureBranch] =
    resolveWith(name, protectedBranches, RecoveryCheck.isSafeReusedRef)

  private def resolveWith(
      name: String,
      protectedBranches: Set[String],
      safeRef: String => Boolean
  ): Either[FeatureBranchRefused, FeatureBranch] =
    val protectedLower =
      (protectedBranches ++ RecoveryCheck.alwaysProtected)
        .map(_.toLowerCase(java.util.Locale.ROOT))
    if protectedLower.contains(name.toLowerCase(java.util.Locale.ROOT)) then
      Left(ProtectedBranchRefused(name))
    else if !safeRef(name) then Left(UnsafeBranchRefRefused(name))
    else Right(name)

  extension (fb: FeatureBranch)
    /** Unwrap for the git layer — call at the `GitTool` call site, not earlier.
      */
    def value: String = fb

/** Common parent for [[FeatureBranch.resolve]] refusal reasons — lets a caller
  * distinguish "protected branch" from "unsafe ref shape" without inspecting a
  * message string.
  */
sealed trait FeatureBranchRefused:
  def name: String

/** `name` was refused because it is a protected branch. */
final case class ProtectedBranchRefused(name: String)
    extends FeatureBranchRefused

/** `name` was refused because it is not a safe git ref — see
  * [[RecoveryCheck.isSafeBranchRef]] ([[FeatureBranch.resolve]]) or
  * [[RecoveryCheck.isSafeReusedRef]] ([[FeatureBranch.resolveReused]]).
  */
final case class UnsafeBranchRefRefused(name: String)
    extends FeatureBranchRefused
