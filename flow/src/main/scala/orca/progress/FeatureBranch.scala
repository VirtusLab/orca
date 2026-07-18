package orca.progress

/** A branch name orca itself may create, commit to, or delete during its own
  * lifecycle bookkeeping (ADR 0018 §2.4/§2.5) — as opposed to any bare `String`
  * git happens to accept. Every [[FeatureBranch]] is guaranteed non-protected
  * (not in the always-protected floor [[RecoveryCheck.alwaysProtected]] unioned
  * with the caller-supplied set, in practice the repo's detected default) AND a
  * safe git ref ([[RecoveryCheck.isSafeBranchRef]]), so the guarantee holds
  * regardless of whether the caller already slugged `name`.
  *
  * Both places orca binds itself to a feature branch mint one through
  * [[FeatureBranch.resolve]]: `FlowLifecycle`'s fresh-run arm and its resume
  * arm ([[RecoveryCheck.validateHeader]], checking an untrusted persisted
  * header). One constructor, one home for the protected-branch check.
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
    * unconditional rather than resting on callers having slugged `name`. Both
    * call sites already pass a shape-safe name, so it is a defensive no-op
    * there.
    */
  def resolve(
      name: String,
      protectedBranches: Set[String]
  ): Either[FeatureBranchRefused, FeatureBranch] =
    val protectedLower =
      (protectedBranches ++ RecoveryCheck.alwaysProtected)
        .map(_.toLowerCase(java.util.Locale.ROOT))
    if protectedLower.contains(name.toLowerCase(java.util.Locale.ROOT)) then
      Left(ProtectedBranchRefused(name))
    else if !RecoveryCheck.isSafeBranchRef(name) then
      Left(UnsafeBranchRefRefused(name))
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

/** `name` was refused because it is not a safe git ref
  * ([[RecoveryCheck.isSafeBranchRef]]).
  */
final case class UnsafeBranchRefRefused(name: String)
    extends FeatureBranchRefused
