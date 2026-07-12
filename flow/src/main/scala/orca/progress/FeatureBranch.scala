package orca.progress

/** A branch name orca itself may create, commit to, or delete during its own
  * lifecycle bookkeeping (ADR 0018 §2.4/§2.5) — as opposed to any bare `String`
  * git happens to accept. Every [[FeatureBranch]] is guaranteed non-protected
  * AND ref-shape-safe: it is NOT a protected branch, where "protected" means
  * the always-protected floor ([[RecoveryCheck.alwaysProtected]] — `main`/
  * `master`) unioned with whatever the caller additionally supplies — in
  * practice the repo's detected default branch (`git.defaultBranch()`), so a
  * repo whose default is e.g. `trunk` is covered too — AND it is a safe git ref
  * ([[RecoveryCheck.isSafeBranchRef]]), so the guarantee holds regardless of
  * whether the caller already slugged `name`.
  *
  * Both places orca binds itself to a feature branch mint one through
  * [[FeatureBranch.resolve]]: `FlowLifecycle`'s fresh-run arm (a
  * strategy/LLM-resolved name that might collide with a protected branch —
  * previously UNCHECKED, the hole this type closes) and its resume arm
  * ([[RecoveryCheck.validateHeader]], checking an untrusted persisted header).
  * One constructor, one policy, one home for the protected-branch check — a
  * protected name can never reach `FlowSetup.featureBranch` from either arm.
  *
  * Deliberately takes `protectedBranches: Set[String]` rather than a `GitTool`,
  * mirroring [[RecoveryCheck.validateHeader]]'s existing shape: it stays pure
  * and unit-testable without a repo fixture, and `tools` (below `flow` in the
  * module graph) never learns this type exists — the git layer stays
  * `String`-typed and flow-oblivious. Callers unwrap via `.value` only at the
  * actual git call site.
  */
opaque type FeatureBranch = String

object FeatureBranch:

  /** Attempt to mint a [[FeatureBranch]] from `name`. Refuses `name` (case-
    * insensitively, matching [[RecoveryCheck]]'s existing normalization — case
    * folded with `Locale.ROOT` so the comparison is locale-independent, e.g.
    * immune to the Turkish-I dotless-i bug) when it is in `protectedBranches`
    * or the always-protected floor ([[RecoveryCheck.alwaysProtected]]); the
    * union mirrors [[RecoveryCheck.validateHeader]]'s protected-branch check
    * exactly, so a name refused for a resumed header is refused for a fresh run
    * too, and vice versa.
    *
    * ALSO refuses `name` when it is not a safe ref shape
    * ([[RecoveryCheck.isSafeBranchRef]]) — this is what makes the type's
    * guarantee unconditional rather than resting on every caller having slugged
    * `name` first. `FlowLifecycle`'s fresh arm always passes an already-slugged
    * name ([[orca.BranchNamingStrategy]] guarantees shape by construction), so
    * this is a no-op there; [[RecoveryCheck.validateHeader]] already
    * shape-checks the untrusted header BEFORE calling `resolve`, so this is a
    * no-op (redundant, defensive) there too.
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
    /** Unwrap for the git layer. The only sanctioned way out of the opaque type
      * — call this at the `GitTool` call site, not earlier.
      */
    def value: String = fb

/** Common parent for [[FeatureBranch.resolve]] refusal reasons — lets a caller
  * pattern-match "protected branch" apart from "unsafe ref shape" instead of
  * inspecting a message string.
  */
sealed trait FeatureBranchRefused:
  def name: String

/** `name` was refused by [[FeatureBranch.resolve]] because it is a protected
  * branch (case-insensitive match against the always-protected floor or the
  * caller-supplied protected set).
  */
final case class ProtectedBranchRefused(name: String)
    extends FeatureBranchRefused

/** `name` was refused by [[FeatureBranch.resolve]] because it is not a safe git
  * ref ([[RecoveryCheck.isSafeBranchRef]]) — not the slug shape
  * [[orca.BranchNamingStrategy.slug]] produces, e.g. not `/`-segmented
  * lowercase-alphanumeric-with-hyphens, or otherwise unsafe as a raw ref.
  */
final case class UnsafeBranchRefRefused(name: String)
    extends FeatureBranchRefused
