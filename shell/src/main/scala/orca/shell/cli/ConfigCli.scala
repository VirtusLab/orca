package orca.shell.cli

import orca.settings.{AgentSettings, AgentSpec}
import orca.shell.actions.{ConfigAction, EditAction, SettingsEditAction}

import Cli.{actionFailure, complete, requireTty, usageFailure, withTerminal}

/** `orca config`'s behavior (ADR 0021 §10): with no role flags, print the
  * current planning/coding/review agents; with any, merge the given subset over
  * the existing settings (or `--force`-rewrite a malformed file). `--edit`
  * bypasses both and hand-edits the tier's settings file instead ([[runEdit]]).
  */
private[cli] object ConfigCli:

  /** `config`'s full dispatch (ADR 0021 §10) — pulled out of the `@main` method
    * so `--edit`'s mutual-exclusion check is unit-testable without a real
    * console. `--edit` can't be combined with any role flag or `--force`:
    * `runEdit` hands the whole file to the user's editor, so a role flag given
    * alongside it would be silently ignored — worth a usage error rather than a
    * surprise. Absent `--edit`, delegates to [[runConfig]] unchanged.
    */
  private[cli] def run(
      globalSettingsPath: os.Path,
      planning: Option[String],
      coding: Option[String],
      review: Option[String],
      force: Boolean,
      edit: Option[String],
      tty: Boolean,
      workDir: os.Path
  ): Int =
    val editConflictsWithFlags =
      planning.isDefined || coding.isDefined || review.isDefined || force
    edit match
      case Some(_) if editConflictsWithFlags =>
        complete(
          Left(
            usageFailure(
              "--edit can't be combined with role flags — edit the file, " +
                "or use the flags, not both"
            )
          )
        )
      case Some(tier) => runEdit(tier, tty, workDir, globalSettingsPath)
      case None =>
        runConfig(globalSettingsPath, planning, coding, review, force)

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

  /** `orca config --edit <tier>`'s behavior (ADR 0021 §10): tty-gate, parse
    * `rawTier` with the shared `project|global` grammar
    * ([[EditCli.parseCustomizeTier]], naming `--edit` rather than `--to` in its
    * error), create the settings file from its template if absent, then open it
    * via [[EditAction.editInPlace]] — same exit-code convention as `orca edit`
    * (the editor child's raw exit code, propagated regardless of whether the
    * edited file re-parses). A malformed result after the edit is a warning to
    * stderr, not a failure: the file is the user's to break, and the editor
    * itself already exited cleanly.
    */
  private[cli] def runEdit(
      rawTier: String,
      tty: Boolean,
      workDir: os.Path,
      globalSettingsPath: os.Path
  ): Int =
    complete:
      for
        _ <- requireTty("config", tty).left.map(usageFailure)
        tier <- EditCli
          .parseCustomizeTier(rawTier, "--edit")
          .left
          .map(usageFailure)
      yield
        val path = SettingsEditAction.pathFor(tier, workDir, globalSettingsPath)
        SettingsEditAction.ensureExists(tier, path, workDir)
        val exit = withTerminal(EditAction.editInPlace(_, path))
        SettingsEditAction.validate(tier, workDir, globalSettingsPath) match
          case Left(error) => Cli.diagnostic(s"warning: $error")
          case Right(_)    => ()
        exit

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
