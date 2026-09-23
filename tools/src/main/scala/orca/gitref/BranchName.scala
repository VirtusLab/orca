package orca.gitref

import orca.agents.JsonData

/** A local branch name that satisfies `git check-ref-format --branch`, so it
  * reaches git as a branch and never as an option, a range or the pseudo-ref
  * `HEAD`. Says nothing about which branches orca may write to — that policy is
  * `orca.progress.FeatureBranch`'s.
  *
  * The JSON codec decodes through [[parse]], so a persisted document holding
  * anything else fails to parse rather than reaching git.
  */
opaque type BranchName = String

object BranchName:

  /** Refuses `raw` with a message naming the violated rule when it is not a
    * valid branch name.
    */
  def parse(raw: String): Either[String, BranchName] =
    refFormatViolation(raw) match
      case Some(rule) => Left(refusal(raw, rule))
      case None       => Right(raw)

  /** The local branch a full ref (`refs/heads/<name>`, as `git symbolic-ref`
    * prints it) names; `None` for any other ref or an invalid name.
    */
  def fromRef(ref: String): Option[BranchName] =
    Option
      .when(ref.startsWith(LocalPrefix))(ref.stripPrefix(LocalPrefix))
      .flatMap(parse(_).toOption)

  /** The message [[parse]] refuses `raw` with, for a caller adding its own rule
    * on top.
    */
  def refusal(raw: String, rule: String): String =
    s"Branch name '$raw' $rule; pick another name."

  given JsonData[BranchName] = JsonData.fromString(parse, identity)

  extension (b: BranchName)
    def value: String = b

    /** The full ref, which git never confuses with a tag of the same name. */
    def ref: String = LocalPrefix + b

  private val LocalPrefix = "refs/heads/"

  private val forbiddenChars = "~^:?*[\\"

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
  private def refFormatViolation(raw: String): Option[String] =
    refFormatRules.collectFirst { case (broken, rule) if broken(raw) => rule }
