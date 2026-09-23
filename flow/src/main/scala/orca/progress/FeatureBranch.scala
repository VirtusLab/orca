package orca.progress

import orca.gitref.BranchName

/** A branch name orca itself may create, commit to, or delete during its own
  * lifecycle bookkeeping (ADR 0018 §2.4/§2.5) — as opposed to any
  * [[BranchName]] git accepts. Every [[FeatureBranch]] is guaranteed
  * non-protected: not in the always-protected floor
  * [[FeatureBranch.alwaysProtected]] unioned with the caller-supplied set, in
  * practice the repo's detected default.
  *
  * [[resolve]]/[[resolveReused]] are the only constructors — one pair, one home
  * for the protected-branch check.
  *
  * Takes `protectedBranches: Set[String]` rather than a `GitTool` so it stays
  * pure and unit-testable without a repo fixture.
  */
opaque type FeatureBranch <: BranchName = BranchName

object FeatureBranch:

  /** Whether `s` is a name orca would mint itself: non-empty, and every
    * `/`-separated segment matches the slug shape `^[a-z0-9][a-z0-9-]*$` (the
    * same charset [[orca.BranchNamingStrategy.slug]] produces). Issue branches
    * like `fix/issue-42` pass; ``, `-x`, `a/..`, `a b`, `Feat` are rejected.
    * Referencing `BranchNamingStrategy.isSlugSegment` keeps producer and
    * validator from drifting.
    */
  def isSafeBranchRef(s: String): Boolean =
    s.nonEmpty && s
      .split("/", -1)
      .forall(orca.BranchNamingStrategy.isSlugSegment)

  /** Branches that are always protected regardless of the repo's configured
    * default — the floor [[RecoveryCheck.validateHeader]] enforces (ADR 0018).
    * The runtime adds the repo's actual default branch on top of these.
    */
  val alwaysProtected: Set[String] = Set("main", "master")

  /** A branch name the user asked for (`--branch`, the shell's prompt): a valid
    * [[BranchName]] outside the always-protected floor. Does not refuse the
    * repo's own default branch, which is known only at run time;
    * `FlowLifecycle` refuses it when minting a [[FeatureBranch]] from this
    * name.
    */
  def parseRequested(raw: String): Either[String, BranchName] =
    BranchName
      .parse(raw)
      .filterOrElse(
        name => !isProtected(name.value, Set.empty),
        BranchName.refusal(raw, "is a protected branch")
      )

  /** [[parseRequested]] for an optional `--branch` flag; `None` passes through.
    */
  def parseRequestedOptional(
      raw: Option[String]
  ): Either[String, Option[BranchName]] =
    raw match
      case None       => Right(None)
      case Some(name) => parseRequested(name).map(Some(_))

  /** Attempt to mint a [[FeatureBranch]] from an orca-minted `name`. Refuses
    * `name` when it is protected (see [[resolveReused]]) or not a slug
    * ([[isSafeBranchRef]]) — the latter makes the guarantee unconditional
    * rather than resting on callers having slugged `name`.
    */
  def resolve(
      name: String,
      protectedBranches: Set[String]
  ): Either[FeatureBranchRefused, FeatureBranch] =
    if isProtected(name, protectedBranches) then
      Left(ProtectedBranchRefused(name))
    else
      BranchName
        .parse(name)
        .toOption
        .filter(_ => isSafeBranchRef(name))
        .toRight(UnsafeBranchRefRefused(name))

  /** Mint a [[FeatureBranch]] for a branch orca did NOT name — the user's
    * current branch in skip-branch mode (ADR 0018 amendment), a `--branch`
    * name, or a resumed header's branch. Refuses `name` (case-insensitively,
    * folded with `Locale.ROOT`) when it is in `protectedBranches` or the
    * always-protected floor; the union mirrors
    * [[RecoveryCheck.validateHeader]]'s check so fresh and resumed runs agree.
    */
  def resolveReused(
      name: BranchName,
      protectedBranches: Set[String]
  ): Either[ProtectedBranchRefused, FeatureBranch] =
    if isProtected(name.value, protectedBranches) then
      Left(ProtectedBranchRefused(name.value))
    else Right(name)

  private def isProtected(
      name: String,
      protectedBranches: Set[String]
  ): Boolean =
    (protectedBranches ++ alwaysProtected)
      .map(_.toLowerCase(java.util.Locale.ROOT))
      .contains(name.toLowerCase(java.util.Locale.ROOT))

/** Common parent for [[FeatureBranch.resolve]] refusal reasons — lets a caller
  * distinguish "protected branch" from "unsafe ref shape" without inspecting a
  * message string.
  */
sealed trait FeatureBranchRefused:
  def name: String

/** `name` was refused because it is a protected branch. */
final case class ProtectedBranchRefused(name: String)
    extends FeatureBranchRefused

/** `name` was refused by [[FeatureBranch.resolve]] because it is not a slug —
  * see [[FeatureBranch.isSafeBranchRef]].
  */
final case class UnsafeBranchRefRefused(name: String)
    extends FeatureBranchRefused
