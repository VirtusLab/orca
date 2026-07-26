package orca

/** Resolved per-project tooling commands (ADR 0019). Each command runs via
  * `bash -c`; an empty list means the task is disabled/unknown — the settings
  * file's explicit `key = off` and an absent key both resolve here to the same
  * empty list; they differ only in whether they re-arm auto-discovery (see
  * `SettingsFile.hasStackLines`).
  */
case class StackSettings(
    format: List[String] = Nil,
    lint: List[String] = Nil,
    test: List[String] = Nil
)

object StackSettings:
  val empty: StackSettings = StackSettings()
