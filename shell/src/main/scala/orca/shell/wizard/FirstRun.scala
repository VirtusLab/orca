package orca.shell.wizard

import orca.settings.{SettingsError, SettingsFile, SettingsScope}

/** Outcome of [[FirstRun.check]]. */
private[shell] enum FirstRunStatus:
  case FirstRun, AlreadyConfigured

/** First-run detection for the welcome wizard (ADR 0021 §4). */
private[shell] object FirstRun:

  /** `Right(FirstRun)` — global settings file absent, or parses cleanly with
    * all three role keys unset (comments-only / empty-values file).
    * `Right(AlreadyConfigured)` — the file already names at least one role.
    * `Left(error)` — the file exists but is malformed; that is NOT first-run,
    * it's surfaced to the caller so it can offer a rewrite instead of silently
    * clobbering a typo'd hand edit.
    */
  def check(
      globalSettingsPath: os.Path
  ): Either[SettingsError, FirstRunStatus] =
    if !os.exists(globalSettingsPath) then Right(FirstRunStatus.FirstRun)
    else
      val content = os.read(globalSettingsPath)
      SettingsFile
        .parse(content, SettingsScope.UserGlobal)
        .map: parsed =>
          val agents = parsed.agents
          if agents.planning.isEmpty && agents.coding.isEmpty && agents.review.isEmpty
          then FirstRunStatus.FirstRun
          else FirstRunStatus.AlreadyConfigured
