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

  extension (b: BranchName) def value: String = b

  private val forbiddenChars = "~^:?*[\\"

  private def violation(raw: String): Option[String] =
    refFormatViolation(raw).orElse(
      Option.when(
        RecoveryCheck.alwaysProtected.contains(raw.toLowerCase(Locale.ROOT))
      )("is a protected branch")
    )

  /** The first `git check-ref-format --branch` rule `raw` breaks, if any.
    * Stricter than git on whitespace and control characters: Unicode ones are
    * refused too.
    */
  private[progress] def refFormatViolation(raw: String): Option[String] =
    val components = raw.split("/", -1).toList
    if raw.isEmpty then Some("is empty")
    else if raw == "HEAD" then Some("is the reserved name HEAD")
    else if raw == "@" then Some("is the reserved name @")
    else if raw.startsWith("-") then Some("must not start with '-'")
    else if raw.exists(_.isControl) then
      Some("must not contain control characters")
    else if raw.exists(_.isWhitespace) then Some("must not contain spaces")
    else if raw.exists(forbiddenChars.contains(_)) then
      Some(s"must not contain any of ${forbiddenChars.mkString(" ")}")
    else if raw.contains("..") then Some("must not contain '..'")
    else if raw.contains("@{") then Some("must not contain '@{'")
    else if components.exists(_.isEmpty) then
      Some("must not start or end with '/' or contain '//'")
    else if components.exists(_.startsWith(".")) then
      Some("must not have a '/'-separated part starting with '.'")
    else if components.exists(_.endsWith(".lock")) then
      Some("must not have a '/'-separated part ending with '.lock'")
    else if raw.endsWith(".") then Some("must not end with '.'")
    else None
