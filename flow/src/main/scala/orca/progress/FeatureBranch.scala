package orca.progress

/** A branch name orca itself may create, commit to, or delete during its own
  * lifecycle bookkeeping (ADR 0018 §2.4/§2.5) — as opposed to any bare `String`
  * git happens to accept. The one thing every [[FeatureBranch]] guarantees: it
  * is NOT a protected branch, where "protected" means the always-protected
  * floor ([[RecoveryCheck.alwaysProtected]] — `main`/ `master`) unioned with
  * whatever the caller additionally supplies — in practice the repo's detected
  * default branch (`git.defaultBranch()`), so a repo whose default is e.g.
  * `trunk` is covered too.
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
    * insensitively, matching [[RecoveryCheck]]'s existing normalization) when
    * it is in `protectedBranches` or the always-protected floor
    * ([[RecoveryCheck.alwaysProtected]]); the union mirrors
    * [[RecoveryCheck.validateHeader]]'s protected-branch check exactly, so a
    * name refused for a resumed header is refused for a fresh run too, and vice
    * versa.
    *
    * Does NOT check ref *shape* (see [[RecoveryCheck.isSafeBranchRef]]) — that
    * is a separate, resume-specific concern for an untrusted header;
    * `FlowLifecycle`'s fresh arm never needs it because
    * [[orca.BranchNamingStrategy]] already guarantees shape by construction.
    */
  def resolve(
      name: String,
      protectedBranches: Set[String]
  ): Either[ProtectedBranchRefused, FeatureBranch] =
    val protectedLower =
      (protectedBranches ++ RecoveryCheck.alwaysProtected).map(_.toLowerCase)
    if protectedLower.contains(name.toLowerCase) then
      Left(ProtectedBranchRefused(name))
    else Right(name)

  extension (fb: FeatureBranch)
    /** Unwrap for the git layer. The only sanctioned way out of the opaque type
      * — call this at the `GitTool` call site, not earlier.
      */
    def value: String = fb

/** `name` was refused by [[FeatureBranch.resolve]] because it is a protected
  * branch (case-insensitive match against the always-protected floor or the
  * caller-supplied protected set).
  */
final case class ProtectedBranchRefused(name: String)
