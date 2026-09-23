package orca.progress

import java.util.Locale

/** A branch name the user asked for, validated at the CLI boundary against `git
  * check-ref-format --branch` and the always-protected floor
  * ([[RecoveryCheck.alwaysProtected]]). Does not refuse the repo's own default
  * branch, which is known only at run time; `FlowLifecycle` refuses it when
  * minting a [[FeatureBranch]] from this name.
  */
opaque type BranchName = String

object BranchName:

  /** Refuses `raw` with a message naming the violated rule when it is not a
    * valid branch name or is a protected branch (case-insensitively).
    */
  def parse(raw: String): Either[String, BranchName] =
    violation(raw) match
      case Some(rule) =>
        Left(s"Branch name '$raw' $rule; pick another name.")
      case None => Right(raw)

  /** [[parse]] for an optional `--branch` flag; `None` passes through. */
  def parseOptional(raw: Option[String]): Either[String, Option[BranchName]] =
    raw match
      case None       => Right(None)
      case Some(name) => parse(name).map(Some(_))

  extension (b: BranchName) def value: String = b

  private val forbiddenChars = "~^:?*[\\"

  private def violation(raw: String): Option[String] =
    refFormatViolation(raw).orElse(
      Option.when(
        RecoveryCheck.alwaysProtected.contains(raw.toLowerCase(Locale.ROOT))
      )("is a protected branch")
    )

  private def components(raw: String): List[String] =
    raw.split("/", -1).toList

  // Ordered: the first broken rule names the refusal.
  private val refFormatRules: List[(String => Boolean, String)] = List(
    (_.isEmpty, "is empty"),
    (_ == "HEAD", "is the reserved name HEAD"),
    (_ == "@", "is the reserved name @"),
    (_.startsWith("-"), "must not start with '-'"),
    (_.exists(_.isControl), "must not contain control characters"),
    (_.exists(_.isWhitespace), "must not contain spaces"),
    (
      _.exists(forbiddenChars.contains(_)),
      s"must not contain any of ${forbiddenChars.mkString(" ")}"
    ),
    (_.contains(".."), "must not contain '..'"),
    (_.contains("@{"), "must not contain '@{'"),
    (
      components(_).exists(_.isEmpty),
      "must not start or end with '/' or contain '//'"
    ),
    (
      components(_).exists(_.startsWith(".")),
      "must not have a '/'-separated part starting with '.'"
    ),
    (
      components(_).exists(_.endsWith(".lock")),
      "must not have a '/'-separated part ending with '.lock'"
    ),
    (_.endsWith("."), "must not end with '.'")
  )

  /** The first `git check-ref-format --branch` rule `raw` breaks, if any.
    * Stricter than git on whitespace and control characters: Unicode ones are
    * refused too.
    */
  private[progress] def refFormatViolation(raw: String): Option[String] =
    refFormatRules.collectFirst { case (broken, rule) if broken(raw) => rule }
