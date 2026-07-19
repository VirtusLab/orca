package orca.shell.cli

import orca.settings.{AgentSettings, AgentSpec}
import orca.shell.actions.ConfigAction

import Cli.{actionFailure, complete, usageFailure}

/** `orca config`'s behavior (ADR 0021 §10): with no role flags, print the
  * current planning/coding/review agents; with any, merge the given subset over
  * the existing settings (or `--force`-rewrite a malformed file).
  */
private[cli] object ConfigCli:

  /** `config`'s full behavior over an explicit settings `path` — pulled out of
    * the `@main` method so tests can point it at a temp file instead of the
    * real user-global settings file.
    */
  private[cli] def runConfig(
      path: os.Path,
      planning: Option[String],
      coding: Option[String],
      review: Option[String],
      force: Boolean
  ): Int =
    if planning.isEmpty && coding.isEmpty && review.isEmpty then
      complete:
        ConfigAction
          .show(path)
          .left
          .map(actionFailure)
          .map: agents =>
            println(renderAgents(agents))
            ExitCodes.Ok
    else
      complete:
        for
          overrides <- parseRoleFlags(planning, coding, review).left
            .map(usageFailure)
          exit <- applyOverrides(path, overrides, force)
        yield exit

  /** Writes `overrides` merged over the file's current agents, treating a
    * malformed file as a rewrite only under `--force` (otherwise an action
    * failure naming the parse error).
    */
  private def applyOverrides(
      path: os.Path,
      overrides: AgentSettings,
      force: Boolean
  ): Either[CliFailure, Int] =
    ConfigAction.show(path) match
      case Left(parseError) if !force =>
        Left(
          actionFailure(
            s"$parseError — pass --force to rewrite it from scratch"
          )
        )
      case Left(_) =>
        ConfigAction.set(path, overrides)
        Right(ExitCodes.Ok)
      case Right(current) =>
        ConfigAction.set(path, overrides.orElse(current))
        Right(ExitCodes.Ok)

  private def parseRole(
      raw: Option[String]
  ): Either[String, Option[AgentSpec]] =
    raw match
      case None    => Right(None)
      case Some(v) => AgentSpec.parse(v).map(Some(_))

  private[cli] def parseRoleFlags(
      planning: Option[String],
      coding: Option[String],
      review: Option[String]
  ): Either[String, AgentSettings] =
    for
      p <- parseRole(planning)
      c <- parseRole(coding)
      r <- parseRole(review)
    yield AgentSettings(p, c, r)

  private[cli] def renderAgents(agents: AgentSettings): String =
    def line(role: String, spec: Option[AgentSpec]): String =
      val value = spec.fold("(not set)"): s =>
        AgentSpec.harnessNameFor(s.backend) + s.model.fold("")(":" + _)
      s"$role: $value"
    List(
      line("planning", agents.planning),
      line("coding", agents.coding),
      line("review", agents.review)
    ).mkString("\n")
