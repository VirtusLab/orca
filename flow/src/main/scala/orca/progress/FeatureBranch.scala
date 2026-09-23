package orca.progress

/** A branch name orca itself may create, commit to, or delete during its own
  * lifecycle bookkeeping (ADR 0018 §2.4/§2.5) — as opposed to any bare `String`
  * git happens to accept. Every [[FeatureBranch]] is guaranteed non-protected
  * (not in the always-protected floor [[FeatureBranch.alwaysProtected]] unioned
  * with the caller-supplied set, in practice the repo's detected default) AND a
  * safe git ref — [[FeatureBranch.isSafeBranchRef]]'s strict slug shape via
  * [[resolve]] for an orca-minted name, or [[FeatureBranch.isSafeReusedRef]]'s
  * weaker shape via [[resolveReused]] for the user's own current branch
  * (skip-branch mode) — so the guarantee holds regardless of which one minted
  * `name`.
  *
  * [[resolve]]/[[resolveReused]] are the only constructors — one pair, one home
  * for the protected-branch check.
  *
  * Takes `protectedBranches: Set[String]` rather than a `GitTool` so it stays
  * pure and unit-testable without a repo fixture, and the git layer stays
  * `String`-typed and flow-oblivious. Callers unwrap via `.value` only at the
  * git call site.
  */
opaque type FeatureBranch = String

object FeatureBranch:

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
    * default — the floor [[RecoveryCheck.validateHeader]] enforces (ADR 0018).
    * The runtime adds the repo's actual default branch on top of these.
    */
  val alwaysProtected: Set[String] = Set("main", "master")

  /** Attempt to mint a [[FeatureBranch]] from `name`. Refuses `name`
    * (case-insensitively, folded with `Locale.ROOT`) when it is in
    * `protectedBranches` or the always-protected floor ([[alwaysProtected]]);
    * the union mirrors [[RecoveryCheck.validateHeader]]'s check so fresh and
    * resumed runs agree.
    *
    * ALSO refuses `name` when it is not a safe ref shape ([[isSafeBranchRef]])
    * — this makes the guarantee unconditional rather than resting on callers
    * having slugged `name`. The one call site (a fresh run's orca-minted name)
    * already passes a shape-safe name, so it is a defensive no-op there.
    */
  def resolve(
      name: String,
      protectedBranches: Set[String]
  ): Either[FeatureBranchRefused, FeatureBranch] =
    resolveWith(name, protectedBranches, isSafeBranchRef)

  /** Mint a [[FeatureBranch]] for a branch orca did NOT create — the user's
    * current branch, reused in skip-branch mode (ADR 0018 amendment) instead of
    * minting a fresh one. `name` passes the weaker [[isSafeReusedRef]] shape
    * check rather than the strict slug one: it was never orca-authored, so it
    * may be mixed-case or carry `/` segments like `feature/JIRA-123`. Still
    * refuses a protected branch.
    */
  def resolveReused(
      name: String,
      protectedBranches: Set[String]
  ): Either[FeatureBranchRefused, FeatureBranch] =
    resolveWith(name, protectedBranches, isSafeReusedRef)

  private def resolveWith(
      name: String,
      protectedBranches: Set[String],
      safeRef: String => Boolean
  ): Either[FeatureBranchRefused, FeatureBranch] =
    val protectedLower =
      (protectedBranches ++ alwaysProtected)
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
  * [[FeatureBranch.isSafeBranchRef]] ([[FeatureBranch.resolve]]) or
  * [[FeatureBranch.isSafeReusedRef]] ([[FeatureBranch.resolveReused]]).
  */
final case class UnsafeBranchRefRefused(name: String)
    extends FeatureBranchRefused
