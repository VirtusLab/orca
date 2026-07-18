package orca.shell.wizard

import orca.settings.{SettingsFile, SettingsScope}

/** First-run detection for the welcome wizard (ADR 0021 §4). */
object FirstRun:

  /** `Right(true)` — global settings file absent, or parses cleanly with all
    * three role keys unset (comments-only / empty-values file). `Right(false)`
    * — the file already names at least one role. `Left(message)` — the file
    * exists but is malformed; that is NOT first-run, it's surfaced to the
    * caller so it can offer a rewrite instead of silently clobbering a typo'd
    * hand edit.
    */
  def check(globalSettingsPath: os.Path): Either[String, Boolean] =
    if !os.exists(globalSettingsPath) then Right(true)
    else
      val content = os.read(globalSettingsPath)
      SettingsFile.parse(content, SettingsScope.UserGlobal) match
        case Left(error) => Left(error.message)
        case Right(parsed) =>
          val agents = parsed.agents
          Right(agents.planning.isEmpty && agents.coding.isEmpty && agents.review.isEmpty)
